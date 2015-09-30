/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.actor._
import akka.pattern._
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.Settings
import ru.infotecs.edi.db.Dal
import ru.infotecs.edi.security.ValidJsonWebToken
import ru.infotecs.edi.service.FileUploading.{AuthFileChunk, FileChunk, Meta}
import ru.infotecs.edi.service.Parser.ParserException
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice}
import ru.infotecs.edi.xml.documents.exceptions.XMLDocumentException

import scala.concurrent.Future
import scala.concurrent.duration._

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
abstract sealed class FileHandler(parent: ActorRef, dal: Dal, originalJwt: ValidJsonWebToken, meta: Meta) extends ActorLogging with Stash {
  implicit val ec = context.system.dispatcher
  implicit val timeout = Timeout(3 seconds)
  var stopOnNextReceiveTimeout = false
  context.setReceiveTimeout(1 minute)

  def handlingUpload(fileChunk: FileChunk): Future[Int]

  def uploadFinishedMessage(fileName: String): Future[ParsedDocument]

  def nextPartHandling(expectingChunk: Int, totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _, _)), jwt) if chunk == expectingChunk => {
      stopOnNextReceiveTimeout = false
      unstashAll()
      val uploadResult = for {
        uploadChunkSize <- handlingUpload(f.fileChunk)
      } yield UnparsedDocumentPart
      uploadResult pipeTo sender
      context become handleNextChunk(expectingChunk + 1, totalChunks)
    }

    case _: AuthFileChunk => stash()

    case ReceiveTimeout => if (stopOnNextReceiveTimeout) {
      context stop self
    } else {
      stopOnNextReceiveTimeout = true
    }
  }

  def lastPartHandling(totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _, _)), jwt) => {
      val uploadResult = for {
        uploadChunkSize <- handlingUpload(f.fileChunk)
        document <- uploadFinishedMessage(fileName)
      } yield document
      uploadResult pipeTo sender foreach (_ => {
        self ! PoisonPill
      })
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
}

/**
 * Кэширование загружаемого файла в памяти.
 */
class FormalizedFileHandler(parent: ActorRef, implicit val dal: Dal, jwt: ValidJsonWebToken, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  implicit val config = Settings(context.system)
  var fileBuilder = ByteString.empty
  val fileStore = context.actorSelection("/user/fileServerClient")
  val fileId = UUID.randomUUID()

  override def uploadFinishedMessage(fileName: String): Future[ParsedDocument] = {
    val senderCompanyId = UUID.fromString(jwt.jwt.cid)
    val senderId = UUID.fromString(jwt.jwt.pid)
    (for {
      xml <- Parser.read(fileBuilder)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, senderCompanyId)
      xml <- Parser.modify(fileId, senderId, senderCompanyId, recipient, xml)
      (bytes, hash) <- Parser.serializeAndHash(xml)
      fileSavedId <- FileMetaInfo.saveFileMeta(fileId, dal, jwt.jwt, xml.getFileName, bytes.length, hash)
      uploadResult <- fileStore ? FileServerMessage(ByteString.fromArray(bytes), 0, bytes.length, jwt, fileId)
    } yield (xml, converted, recipient)) map {
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
            xml.getPrintedFormId,
            invoiceChangeNumber,
            invoiceCorrectionNumber
          )
        )
      }
    } recoverWith {
      case e: XMLDocumentException =>
        fileStore ? FileServerMessage(fileBuilder, 0 , fileBuilder.size, jwt, fileId) map {_ =>
          InformalDocument(fileId.toString, fileName, meta.mediaType)
        } recover {
          case e: FileServerClientException => ParsingError(fileName, "")
        }
      case e: ParserException => Future.successful(ParsingError(fileName, e.getErrorMessage))
      case e: FileServerClientException => Future.successful(ParsingError(fileName, ""))
    }
  }

  def handlingUpload(fileChunk: FileChunk) = {
    // выполнять парсинг файла
    val uploadChunk = fileChunk.file.entity.data.toByteString
    fileBuilder = fileBuilder ++ uploadChunk
    Future.successful(uploadChunk.size)
  }
}

/**
 * Передача частей файла в персистентное хранилище.
 */
class InformalFileHandler(parent: ActorRef, dal: Dal, jwt: ValidJsonWebToken, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  val fileServerConnector = context.actorSelection("/user/fileServerClient")
  var offset: Long = 0
  var fileIdFuture: Future[UUID] = FileMetaInfo.saveFileMeta(UUID.randomUUID(), dal, jwt.jwt, meta)

  override def uploadFinishedMessage(fileName: String): Future[ParsedDocument] = {
    self ! PoisonPill
    for {
      fileId <- fileIdFuture
    } yield InformalDocument(fileId.toString, fileName, meta.mediaType)
  }

  def handlingUpload(fileChunk: FileChunk) = {
    val uploadChunk = fileChunk.file.entity.data.toByteString
    val currentOffset = offset
    offset += uploadChunk.size
    for {
      fileId <- fileIdFuture
      fileServerResponse <-
        fileServerConnector ? FileServerMessage(uploadChunk, currentOffset, fileChunk.meta.size, jwt, fileId)
    } yield uploadChunk.size
  }

}

