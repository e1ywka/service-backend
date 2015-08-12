/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.Actor
import akka.util.ByteString
import ru.infotecs.edi.service.Parser.{InvalidXml, Parsing, ParsingResult, ValidXml}
import ru.infotecs.edi.xml.documents.XMLDocumentReader
import ru.infotecs.edi.xml.documents.clientDocuments.ClientDocument

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Parser {

  trait ParsingResult

  case object Parsing extends ParsingResult

  case object ValidXml extends ParsingResult

  case class InvalidXml(message: String) extends ParsingResult

  def validate(b: ByteString) = {
    val xmlDocument = XMLDocumentReader.read(b.iterator.asInputStream)
    if (!xmlDocument.isInstanceOf[ClientDocument]) {
      throw new Exception("Not a client document")
    }
    xmlDocument.validate()
    xmlDocument
  }
}

/**
 * Actor performs parsing xml documents and checks its validity.
 */
class Parser extends Actor {

  implicit val ec = context.dispatcher

  var file: ByteString = _

  def receive: Receive = {
    case f: ByteString => Future {
      Parser.validate(f)
    } andThen {
      case Success(doc: ClientDocument) => doc.write(Console.out)
      case Failure(e) => println(e.getMessage)
    } recoverWith {
      case e: Exception => {
        context become parsingResult(InvalidXml(e.getMessage))
        Future.failed(e)
      }
    } foreach (_ => {
      context become parsingResult(ValidXml)
    })
      context become parsingResult(Parsing)
  }

  def parsingResult(parsingResult: ParsingResult): Receive = {
    case _ => parsingResult
  }
}
