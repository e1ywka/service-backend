/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.util.ByteString
import ru.infotecs.edi.UUIDUtils.noDashString
import ru.infotecs.edi.db.Dal
import ru.infotecs.edi.xml.documents.XMLDocumentReader
import ru.infotecs.edi.xml.documents.clientDocuments.ClientDocument
import ru.infotecs.edi.xml.documents.converter.ClientDocumentConverter
import ru.infotecs.edi.xml.documents.elements.signatory.{EntrepreneurSignatory, LegalEntitySignatory, Signatory}
import ru.infotecs.edi.xml.documents.elements.{EntrepreneurInfo, LegalEntityInfo, PersonName}

import scala.concurrent.Future
import scala.util.Try

object Parser {

  import scala.concurrent.ExecutionContext.Implicits.global

  val OperatorFnsId: String = "2АН"
  val OperatorCompanyName = ""
  val OperatorCompanyInn = ""

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

  def modify(fileId: UUID,
             senderPersonId: UUID,
             senderCompanyId: UUID,
             recipientCompanyId: Option[UUID],
             xmlDocument: ClientDocument)(implicit dal: Dal): Future[ClientDocument] = {
    import dal.driver.api._

    val signerFuture = dal.database.run(dal.findPersonById(senderPersonId).result.head)
    val senderCompanyFuture = dal.database.run(dal.find(senderCompanyId).result.head)
    for {
      signer <- signerFuture
      senderCompany <- senderCompanyFuture
    } yield {
      xmlDocument.setDocumentFileId(fileId)
      xmlDocument.setSenderId(OperatorFnsId + noDashString(senderCompanyId))
      if (recipientCompanyId.isDefined) {
        xmlDocument.setRecipientId(OperatorFnsId + noDashString(recipientCompanyId.get))
      }
      xmlDocument.setOperatorInfo(OperatorFnsId, OperatorCompanyName, OperatorCompanyInn)

      val personName = new PersonName(signer.lastName, signer.firstName, signer.middleName.orNull)
      val signatory: Signatory = {
        if (senderCompany.isEntrepreneur) {
          new EntrepreneurSignatory(senderCompany.inn,
            personName,
            senderCompany.entrepreneurGosRegCertificate.orNull)
        } else {
          new LegalEntitySignatory(senderCompany.inn,
            personName,
            null)
        }
      }
      xmlDocument.setSignatory(signatory)

      if (xmlDocument.getExternalInteractionId == null) {
        xmlDocument.setExternalInteractionId(UUID.randomUUID())
      }
      xmlDocument
    }
  }
}