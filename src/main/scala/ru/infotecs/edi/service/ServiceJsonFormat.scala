/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import spray.json.DefaultJsonProtocol._

abstract sealed class ParsedDocument

case object UnparsedDocumentPart extends ParsedDocument

/**
 * Informal document.
 * @param fileId id in database.
 * @param fileName user specified file name.
 * @param isFormal is document formalized. must be false.
 */
case class InformalDocument(fileId: String, fileName: String, mediaType: String, isFormal: Boolean = false) extends ParsedDocument

/**
 * Formal document.
 * @param fileId id in database.
 * @param fileName formalized file name.
 * @param formalType recognized type of formal document.
 * @param isConverted flag means document was converted in from deprecated format.
 * @param documentName formal document name.
 * @param recipientId recipient id, if any specified.
 * @param isFormal is document formalized. must be true.
 */
case class FormalDocument(fileId: String, fileName: String, formalType: String, isConverted: Boolean,
                          documentName: String, recipientId: Option[String], params: FormalDocumentParams,
                          isFormal: Boolean = true, mediaType: String = "text/xml") extends ParsedDocument

case class FormalDocumentParams(primaryFormalNumber: String,
                                primaryFormalDate: String,
                                externalInteractionId: String,
                                printedForm: String,
                                changeNumber: Option[String],
                                invoiceCorrectionNumber: Option[String])

/**
 * Error parsing formal document.
 * @param fileName user specified file name.
 * @param errorMessage message contains detailed error description.
 */
case class ParsingError(fileName: String, errorMessage: String) extends ParsedDocument

object ServiceJsonFormat {
  implicit val unformalDocumentFormat = jsonFormat4(InformalDocument)
  implicit val formalDocumentParamsFormat = jsonFormat6(FormalDocumentParams)
  implicit val formalDocumentFormat = jsonFormat9(FormalDocument)
  implicit val parsingErrorFormat = jsonFormat2(ParsingError)
}
