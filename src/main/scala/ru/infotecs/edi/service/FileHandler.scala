/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.ByteArrayOutputStream
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

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

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
  var fileIdFuture: Future[UUID] = FileMetaInfo.saveFileMeta(dal, originalJwt.jwt, meta)
  context.setReceiveTimeout(1 minute)

  def handlingUpload(fileChunk: FileChunk): Future[Int]

  def uploadFinishedMessage(fileName: String, fileId: UUID): Future[ParsedDocument]

  def nextPartHandling(expectingChunk: Int, totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _)), jwt) if chunk == expectingChunk => {
      stopOnNextReceiveTimeout = false
      unstashAll()
      val uploadResult = for {
        uploadChunkSize <- handlingUpload(f.fileChunk)
      } yield UnparsedDocumentPart
      uploadResult pipeTo sender
      context become handleNextChunk(expectingChunk + 1, totalChunks)
    }

    case ReceiveTimeout => if (stopOnNextReceiveTimeout) {
      context stop self
    } else {
      stopOnNextReceiveTimeout = true
    }
  }

  def lastPartHandling(totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, _), filePart, Meta(fileName, _, _)), jwt) => {
      val uploadResult = for {
        fileId <- fileIdFuture
        uploadChunkSize <- handlingUpload(f.fileChunk)
        document <- uploadFinishedMessage(fileName, fileId)
      } yield document
      uploadResult pipeTo sender
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
class FormalizedFileHandler(parent: ActorRef, implicit val dal: Dal, jwt: ValidJsonWebToken, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  implicit val config = Settings(context.system)
  var fileBuilder = ByteString.empty
  val fileStore = context.actorSelection("/user/fileServerClient")

  override def uploadFinishedMessage(fileName: String, fileId: UUID): Future[ParsedDocument] = {
    val senderCompanyId = UUID.fromString(jwt.jwt.cid)
    val senderId = UUID.fromString(jwt.jwt.pid)
    (for {
      xml <- Parser.read(fileBuilder)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, senderCompanyId)
      xml <- Parser.modify(fileId, senderId, senderCompanyId, recipient, xml)
      uploadResult <- {
        val baos = new ByteArrayOutputStream()
        xml.write(baos)
        val byteArray = baos.toByteArray
        fileStore ? FileServerMessage(ByteString.fromArray(byteArray), 0, byteArray.length, jwt, fileId)
      }
    } yield (xml, converted, recipient)) andThen {
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
    } recoverWith {
      case e: XMLDocumentException =>
        fileStore ? FileServerMessage(fileBuilder, 0 , fileBuilder.size, jwt, fileId) map {_ =>
          InformalDocument(fileId.toString, fileName)
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
  val offset: Int = 0

  override def uploadFinishedMessage(fileName: String, fileId: UUID): Future[ParsedDocument] = {
    //context unwatch fileServerConnector
    //fileServerConnector ! Finish
    self ! PoisonPill
    Future.successful(InformalDocument(fileId.toString, fileName))
  }

  def handlingUpload(fileChunk: FileChunk) = {
    val uploadChunk = fileChunk.file.entity.data.toByteString
    for {
      fileId <- fileIdFuture
      fileServerResponse <-
        fileServerConnector ? FileServerMessage(uploadChunk, offset, fileChunk.meta.size, jwt, fileId)
    } yield uploadChunk.size
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    //context watch fileServerConnector
  }
}

