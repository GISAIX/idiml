package com.idibon.ml.train

import com.idibon.ml.alloy.ScalaJarAlloy
import com.idibon.ml.common.{BaseEngine, TrainEngine}
import com.idibon.ml.feature.bagofwords.BagOfWordsTransformer
import com.idibon.ml.feature.indexer.IndexTransformer
import com.idibon.ml.feature.tokenizer.TokenTransformer
import com.idibon.ml.feature.{ContentExtractor, FeaturePipelineBuilder}
import com.idibon.ml.predict.PredictModel
import com.idibon.ml.predict.ensemble.EnsembleModel
import com.idibon.ml.predict.ml.IdibonLogisticRegressionModel
import com.idibon.ml.predict.rules.DocumentRules
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.ml.classification.{IdibonSparkLogisticRegressionModelWrapper, LogisticRegressionModel, LogisticRegression}
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{parse, render, compact}

import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable
import scala.collection.mutable.HashMap

/** EmbeddedEngine
  *
  * Performs training, given a set of documents and annotations.
  *
  */
class EmbeddedEngine extends BaseEngine with TrainEngine with StrictLogging {

  /** Produces an RDD of LabeledPoints for each distinct label name.
    *
    * @param labeledPoints: a set of training data
    * @return a trained model based on the MLlib logistic regression model with LBFGS, trained on the provided
    *         dataset
    */
  def fitLogisticRegressionModel(sc: SparkContext, labeledPoints: RDD[LabeledPoint]): LogisticRegressionModel = {
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    val data = sqlContext.createDataFrame(labeledPoints)

    // set more parameters here
    val trainer = createTrainer()
    trainer.fit(data).bestModel.asInstanceOf[LogisticRegressionModel]
  }

  def createTrainer() = {
    // TODO: make these parameters more realistic
    val lr = new LogisticRegression().setMaxIter(100)
    // Print out the parameters, documentation, and any default values.
    logger.info("LogisticRegression parameters:\n" + lr.explainParams() + "\n")
    // We use a ParamGridBuilder to construct a grid of parameters to search over.
    // 5 values for lr.regParam,
    // 1 x 5 = 5 parameter settings for CrossValidator to choose from.
    val paramGrid = new ParamGridBuilder()
      // TODO: make these parameters more realistic
      .addGrid(lr.regParam, Array(0.01, 0.05, 0.1, 0.20, 0.35, 0.5))
      .addGrid(lr.elasticNetParam, Array(0.0, 0.2, 0.5, 0.7, 0.9, 1.0))
      .build()

    // We now treat the LR as an Estimator, wrapping it in a CrossValidator instance.
    // This will allow us to only choose parameters for the LR stage.
    // A CrossValidator requires an Estimator, a set of Estimator ParamMaps, and an Evaluator.
    // Note that the evaluator here is a BinaryClassificationEvaluator and its default metric
    // is areaUnderROC.
    val cv = new CrossValidator()
      .setEstimator(lr)
      // TODO: decide on best evaluator (this uses ROC)
      .setEvaluator(new BinaryClassificationEvaluator())
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(6) // Use 3+ in practice
    cv
  }

  /** Trains a model and saves it at the given filesystem location
    *
    * @param infilePath: the location of the ididat dump file generated by the export_training_to_idiml.rb tool,
    *                  found in idibin
    * @param modelStoragePath: the filesystem path for saving models
    */
  def start(infilePath: String, modelStoragePath: String): Unit = {
    val startTime = System.currentTimeMillis()
    logger.info(s"Reading from ${infilePath} and outputting to ${modelStoragePath}")

    // Define a pipeline that generates feature vectors
    val pipeline = (FeaturePipelineBuilder.named("FeaturePipeline")
      += (FeaturePipelineBuilder.entry("convertToIndex", new IndexTransformer, "bagOfWords"))
      += (FeaturePipelineBuilder.entry("bagOfWords", new BagOfWordsTransformer, "convertToTokens"))
      += (FeaturePipelineBuilder.entry("convertToTokens", new TokenTransformer, "contentExtractor"))
      += (FeaturePipelineBuilder.entry("contentExtractor", new ContentExtractor, "$document"))
      := ("convertToIndex"))

    val training: Option[HashMap[String, RDD[LabeledPoint]]] = new RDDGenerator()
      .getLabeledPointRDDs(sparkContext, infilePath, pipeline)
    if (training.isEmpty) {
      println("Error generating training points; Exiting.")
      return
    }
    val labelModelMap = new mutable.HashMap[String, PredictModel]()
    val labelToUUID = new mutable.HashMap[String, String]()
    val trainingData = training.get

    // to make it easier to see everything together about the models trained. Build an atomic
    // log line.
    val atomicLogLine = new StringBuffer()
    trainingData.par.map {
      case (label, labeledPoints) => {
        // base LR model
        val model = fitLogisticRegressionModel(sparkContext, labeledPoints)
        // append info to atomic log line
        atomicLogLine.append(s"Model for $label was fit using parameters: ${model.parent.extractParamMap}\n")
        // wrap into one we want
        val wrapper = IdibonSparkLogisticRegressionModelWrapper.wrap(model)
        // create PredictModel for label:
        // LR
        val idiModel = new IdibonLogisticRegressionModel(label, wrapper, pipeline)
        // Rule
        val ruleModel = new DocumentRules(label, List())
        // Ensemble
        val ensembleModel = new EnsembleModel(label, List[PredictModel](idiModel, ruleModel))
        (label, ensembleModel)
      }
      // remove parallel, and then stick it in the map
    }.toList.foreach(x => {
      labelModelMap.put(x._1, x._2)
      // TODO: UUID from task config
      labelToUUID.put(x._1, x._1)
    })
    // log training information atomically
    logger.info(atomicLogLine.toString())

    // create the alloy for the task
    val alloy = new ScalaJarAlloy(labelModelMap, labelToUUID)
    // TODO: modify name of where it gets stored.
    // save the alloy
    alloy.save(modelStoragePath)
    logger.info(s"Training completed in ${System.currentTimeMillis() - startTime}ms!")
  }
}