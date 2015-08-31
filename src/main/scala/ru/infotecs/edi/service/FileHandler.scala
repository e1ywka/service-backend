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
import ru.infotecs.edi.service.FileUploading.{AuthFileChunk, FileChunk, Meta}
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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
abstract sealed class FileHandler(parent: ActorRef, dal: Dal, originalJwt: Jwt, meta: Meta) extends ActorLogging with Stash {
  implicit val ec = context.system.dispatcher
  var stopOnNextReceiveTimeout = false
  var fileIdFuture: Future[UUID] = FileMetaInfo.saveFileMeta(dal, originalJwt, meta)
  context.setReceiveTimeout(1 minute)

  def handlingUpload(fileChunk: FileChunk): Unit

  def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[ParsedDocument]

  def nextPartHandling(expectingChunk: Int, totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _)), jwt) if chunk == expectingChunk => {
      stopOnNextReceiveTimeout = false
      unstashAll()
      handlingUpload(f.fileChunk)
      log.debug("File chunk uploaded")
      sender ! UnparsedDocumentPart
      context become handleNextChunk(expectingChunk + 1, totalChunks)
    }

    case f: AuthFileChunk => stash(); log.debug("File chunk stashed")

    case ReceiveTimeout => if (stopOnNextReceiveTimeout) {
      context stop self
    } else {
      stopOnNextReceiveTimeout = true
    }
  }

  def lastPartHandling(totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _)), jwt) => {
      handlingUpload(f.fileChunk)
      log.debug("Upload finished")
      fileIdFuture flatMap { fileId =>
        uploadFinishedMessage(fileName, jwt, fileId)
      } pipeTo sender
    }

    case ReceiveTimeout => if (stopOnNextReceiveTimeout) {
      context stop self
    } else {
      stopOnNextReceiveTimeout = true
    }
  }

  val idle: Receive = {
    case FileHandler.Init(chunks) => {
      context setReceiveTimeout (15 minutes)
      context become handleNextChunk(0, chunks)
    }
    case f@AuthFileChunk => stash()
    case ReceiveTimeout => context stop self
  }

  def receive = idle

  def handleNextChunk(nextChunk: Int, totalChunks: Int): Receive = {
    if (nextChunk == (totalChunks - 1)) {
      lastPartHandling(totalChunks)
    } else {
      nextPartHandling(nextChunk, totalChunks)
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    Await.ready(fileIdFuture, 2 seconds)
  }
}

/**
 * Кэширование загружаемого файла в памяти.
 */
class FormalizedFileHandler(parent: ActorRef, implicit val dal: Dal, jwt: Jwt, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  var fileBuilder = ByteString.empty
  val fileStore = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[ParsedDocument] = {
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
      case Failure(e) => throw e //todo save anyway as InformalDocument
    } andThen {
      case _ => self ! PoisonPill
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
            xml.getExternalInteractionId.toString,
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
class InformalFileHandler(parent: ActorRef, dal: Dal, jwt: Jwt, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[ParsedDocument] = {
    context unwatch fileServerConnector
    fileServerConnector ! Finish
    self ! PoisonPill
    Future.successful(InformalDocument(fileId.toString, fileName))
  }

  def handlingUpload(fileChunk: FileChunk) = {
    fileServerConnector ! fileChunk.file.entity.data.toByteString
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    context watch fileServerConnector
  }
}

