/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.actor.Actor
import akka.util.ByteString
import ru.infotecs.edi.db.Dal
import ru.infotecs.edi.service.Parser.{InvalidXml, Parsing, ParsingResult, ValidXml}
import ru.infotecs.edi.xml.documents.XMLDocumentReader
import ru.infotecs.edi.xml.documents.clientDocuments.ClientDocument
import ru.infotecs.edi.xml.documents.converter.ClientDocumentConverter
import ru.infotecs.edi.xml.documents.elements.{EntrepreneurInfo, LegalEntityInfo}


import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Parser {
  import scala.concurrent.ExecutionContext.Implicits.global

  trait ParsingResult

  case object Parsing extends ParsingResult

  case object ValidXml extends ParsingResult

  case class InvalidXml(message: String) extends ParsingResult

  case class Doc(format: String, converted: Boolean, warning: String)

  def read(b: ByteString): Future[ClientDocument] = Future.fromTry(Try {
    val xmlDocument = XMLDocumentReader.read(b.iterator.asInputStream)
    if (!xmlDocument.isInstanceOf[ClientDocument]) {
      throw new Exception("Not a client document")
    }
    xmlDocument.asInstanceOf[ClientDocument]
  })

  def convert(xmlDocument: ClientDocument): Future[(Boolean, ClientDocument)] = Future.fromTry(Try {
    if (ClientDocumentConverter.requiresConversion(xmlDocument)) {
      val convertedXml = ClientDocumentConverter.convert(xmlDocument)
      (true, convertedXml)
    } else {
      (false, xmlDocument)
    }
  })

  def checkRequisites(xmlDocument: ClientDocument, senderCompanyId: UUID)(implicit dal: Dal): Future[(Option[UUID], ClientDocument)] = {
    import dal.driver.api._

    def validateSenderCompany(): Future[Boolean] = {
      for {
        senderCompany <- dal.database.run(
          dal.find(senderCompanyId).result.headOption
        )
      } yield senderCompany.isDefined && senderCompany.get.inn.equals(xmlDocument.getSender.getInn)
    }
    def validateRecipientCompany(): Future[Option[UUID]] = {
      if (xmlDocument.getRecipient == null) {
        Future.successful(None)
      } else {
        val q = xmlDocument.getRecipient match {
          case r: LegalEntityInfo => dal.find(r.getInn, Some(r.getKpp))
          case r: EntrepreneurInfo => dal.find(r.getInn, None)
        }
        dal.database.run(q.map(_.id).result.headOption)
      }
    }

    def checkFriendship(recipientCompanyId: Option[UUID]): Future[Boolean] = {
      recipientCompanyId match {
        case Some(id) => dal.database.run(dal.companiesAreFriends(senderCompanyId, id).head)
        case None => Future.successful(true)
      }
    }

    val senderValidation = validateSenderCompany()
    val recipientValidation = validateRecipientCompany()

    for {
      isSenderValid <- senderValidation
      recipient <- recipientValidation
      areFriends <- checkFriendship(recipient)
    } yield {
      if (!isSenderValid) {
        throw new Exception
      }
      if (!areFriends) {
        throw new Exception
      }
      (recipient, xmlDocument)
    }
  }

  def modify(xmlDocument: ClientDocument): Future[ClientDocument] = Future.successful(xmlDocument)
}

/**
 * Actor performs parsing xml documents and checks its validity.
 */
class Parser extends Actor {

  implicit val ec = context.dispatcher

  var file: ByteString = _

  def receive: Receive = {
    case f: ByteString => Future {
      Parser.read(f)
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
