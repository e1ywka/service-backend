/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.ByteArrayOutputStream
import java.util.UUID

import akka.actor._
import akka.pattern._
import akka.util.ByteString
import ru.infotecs.edi.db.Dal
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading.{FileChunkUploaded, Meta, FileChunk, AuthFileChunk}
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice}
import ru.infotecs.edi.xml.documents.exceptions.ParseDocumentException

import scala.concurrent.Future
import scala.util.{Failure, Success}

object FileHandler {

  /**
   * Инициализация загрузки.
   * @param totalChunks количество частей файла.
   */
  case class Init(totalChunks: Int)

  case class FlushTo(recipient: ActorRef)

}

/**
 * Базовый класс для обработки загружаемого файла.
 * Выполняет контроль очереди частей файла.
 *
 * param parent
 */
abstract sealed class FileHandler(parent: ActorRef, fileId: UUID) extends Actor with Stash {
  var nextChunk = 0
  var totalChunks = 0
  implicit val ec = context.system.dispatcher

  def handlingUpload(fileChunk: FileChunk): Unit

  def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[Any]

  val handling: Receive = {
    case f@AuthFileChunk(FileChunk((chunk, chunks), filePart, Meta(fileName, _, _)), jwt) => {
      unstashAll()
      if (chunk == nextChunk) {
        nextChunk += 1
        // выполнять парсинг файла
        val recipient = sender()
        handlingUpload(f.fileChunk)
        if (nextChunk == totalChunks) {
          uploadFinishedMessage(fileName, jwt) pipeTo recipient
          context.stop(self)
        } else {
          recipient ! FileChunkUploaded
        }
      } else {
        stash()
      }
    }
  }

  val idle: Receive = {
    case FileHandler.Init(chunks) => {
      totalChunks = chunks
      context become handling
    }
    case chunk@FileChunk => sender ! Status.Failure(new Exception("FileHandler is in Idle state"))
  }

  def receive = idle
}

/**
 * Кэширование загружаемого файла в памяти.
 */
class FormalizedFileHandler(parent: ActorRef, fileId: UUID, implicit val dal: Dal) extends FileHandler(parent, fileId) {

  var fileBuilder = ByteString.empty
  val fileStore = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[Any] = {
    val senderCompanyId = UUID.fromString(jwt.cid)
    val senderId = UUID.fromString(jwt.pid)
    (for {
      xml <- Parser.read(fileBuilder)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, senderCompanyId)
      xml <- Parser.modify(fileId, senderId, senderCompanyId, recipient, xml)
    } yield (xml, converted, recipient)) andThen {
      case Success((xml, _, _)) => {
        val baos = new ByteArrayOutputStream()
        xml.write(baos)
        fileStore ! ByteString.fromArray(baos.toByteArray)
        fileStore ! Finish
        //todo update db file#name
      }
      case Failure(e: ParseDocumentException) => //todo save anyway as InformalDocument
    } map {
      case (xml, converted, recipient) => {
        val invoiceChangeNumber = xml match {
          case x: AbstractInvoice if x.getChangeNumber != null => Some(x.getChangeNumber)
          case _ => None
        }
        val invoiceCorrectionNumber = xml match {
          case x: AbstractCorrectiveInvoice if x.getNumber != null => Some(x.getNumber)
          case _ => None
        }
        FormalDocument(fileId.toString,
          fileName,
          xml.getType.getFnsPrefix,
          converted,
          xml.getName,
          recipient.map(_.toString),
          FormalDocumentParams(xml.getPrimaryNumber,
            xml.getPrimaryDate.toInstant.getEpochSecond.toString,
            //todo при реализации метода Parser.modify это поле будет всегда указываться
            "",
            invoiceChangeNumber,
            invoiceCorrectionNumber
          )
        )
      }
    }
  }

  def handlingUpload(fileChunk: FileChunk) = {
    import fileChunk._
    // выполнять парсинг файла
    fileBuilder = fileBuilder ++ file.entity.data.toByteString
  }
}

/**
 * Передача частей файла в персистентное хранилище.
 */
class InformalFileHandler(parent: ActorRef, fileId: UUID) extends FileHandler(parent, fileId) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[Any] = {
    context unwatch fileServerConnector
    fileServerConnector ! Finish
    Future.successful(InformalDocument(fileId.toString, fileName))
  }

  def handlingUpload(fileChunk: FileChunk) = {
    fileServerConnector ! fileChunk.file.entity.data.toByteString
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    context watch fileServerConnector
  }
}

