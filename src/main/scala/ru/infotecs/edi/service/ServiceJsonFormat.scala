/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import spray.json.DefaultJsonProtocol

case class UnformalDocument(fileId: String, fileName: String, isFormal: Boolean = false)

case class FormalDocument(fileId: String, fileName: String, formalType: String, isConverted: Boolean,
                          recipientId: String, isFormal: Boolean = true)

case class ParsingError(fileName: String, errorMessage: String)

object ServiceJsonFormat extends DefaultJsonProtocol {
  implicit val unformalDocumentFormat = jsonFormat3(UnformalDocument)
  implicit val formalDocumentFormat = jsonFormat6(FormalDocument)
  implicit val parsingErrorFormat = jsonFormat2(ParsingError)
}
