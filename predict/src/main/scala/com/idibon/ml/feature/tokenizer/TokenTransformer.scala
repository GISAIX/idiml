import scala.collection.immutable.StringLike
import scala.reflect.runtime.universe._

import com.idibon.ml.feature.{Feature,FeatureTransformer}

package com.idibon.ml.feature.tokenizer {

  /** Tokenization FeatureTransformer */
  class TokenTransformer extends FeatureTransformer {

    /** Tokenizes an array of strings stored in the "content" key of the map
      *
      * If more than one input string is provided, the result will be the
      * concatenation of the tokenized results of all input strings.
      *
      * @param contents  one or more features internally represented as
      *   strings that should be tokenized
      * @return all entries in contents tokenized and concatenated into one list
      */
    def apply(contents: Seq[Feature[String]]): Seq[Token] = {
      // use foldLeft rather than reduce to handle the empty-list case
      contents.foldLeft(List[Token]())((tokens, content) => {
        // append the tokens in content to the accumulated list of tokens
        tokens ++ ICUTokenizer.tokenize(content.get)
      })
    }
  }
}