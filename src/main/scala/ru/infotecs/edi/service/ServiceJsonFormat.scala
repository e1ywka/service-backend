/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import spray.json.DefaultJsonProtocol

abstract class Document(fileId: String, fileName: String, size: Long, docType: String)

case class UnformalDocument(fileId: String, fileName: String, size: Long) extends Document(fileId, fileName, size, "Unformal")

case class FormalDocument(fileId: String, fileName: String, size: Long, docType: String) extends Document(fileId, fileName, size, docType)

object ServiceJsonFormat extends DefaultJsonProtocol {
  implicit val unformalDocumentFormat = jsonFormat3(UnformalDocument)
  implicit val formalDocumentFormat = jsonFormat4(FormalDocument)
}
