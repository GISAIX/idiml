package com.idibon.ml.feature.language

import org.json4s._
import org.scalatest.{Matchers, FunSpec}
import com.idibon.ml.feature.Feature
import com.idibon.ml.feature.contenttype.{ContentType, ContentTypeCode}

class LanguageDetectorSpec extends FunSpec with Matchers {

  describe("apply") {

    val transform = new LanguageDetector

    it("should use .metadata.iso_639_1 if present") {

      val document = JObject(List(
        JField("content", JString("This is an English document")),
        JField("metadata", JObject(List(
          JField("iso_639_1", JString("zh-Hant"))
        )))))
      transform(document, ContentType(ContentTypeCode.PlainText)) shouldBe LanguageCode(Some("zho"))
    }

    it("should use auto-detection if no metadata is present") {
      val document = JObject(List(
        JField("content", JString("Ceci est une phrase en français"))))
      transform(document, ContentType(ContentTypeCode.PlainText)) shouldBe LanguageCode(Some("fra"))
    }
  }

  describe("normalize") {
    it("should return None if the language is invalid") {
      LanguageCode.normalize("!!") shouldBe None
    }
  }
}
