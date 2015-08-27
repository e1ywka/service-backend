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
import ru.infotecs.edi.security.{JsonWebToken, Jwt}
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading.{FileChunkUploaded, Meta, FileChunk, AuthFileChunk}
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice}
import ru.infotecs.edi.xml.documents.exceptions.ParseDocumentException

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
abstract sealed class FileHandler(parent: ActorRef, dal: Dal, originalJwt: Jwt, meta: Meta) extends Actor with Stash {
  implicit val ec = context.system.dispatcher
  var nextChunk = 0
  var stopOnNextReceiveTimeout = false
  var fileIdFuture: Future[UUID] = FileMetaInfo.saveFileMeta(dal, originalJwt, meta) andThen {
    case Failure(e) => throw e
  }
  context.setReceiveTimeout(1 minute)

  def handlingUpload(fileChunk: FileChunk): Unit

  def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[Any]

  def handling(totalChunks: Int): Receive = {
    case f@AuthFileChunk(FileChunk((chunk, chunks), filePart, Meta(fileName, _, _)), jwt) => {
      stopOnNextReceiveTimeout = false
      unstashAll()
      if (chunk == nextChunk) {
        nextChunk += 1
        fileIdFuture flatMap { fileId =>
          handlingUpload(f.fileChunk)
          if (nextChunk == totalChunks) {
            uploadFinishedMessage(fileName, jwt, fileId)
          } else {
            Future.successful(FileChunkUploaded)
          }
        } pipeTo sender()
      } else {
        stash()
      }
    }
    case ReceiveTimeout => if (stopOnNextReceiveTimeout) {
      context stop self
    } else {
      stopOnNextReceiveTimeout = true
    }
  }

  val idle: Receive = {
    case FileHandler.Init(chunks) => {
      context setReceiveTimeout(15 minutes)
      context become handling(chunks)
    }
    case chunk@FileChunk => sender ! Status.Failure(new Exception("FileHandler is in Idle state"))
    case ReceiveTimeout => context stop self
  }

  def receive = idle

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
  }
}

/**
 * Кэширование загружаемого файла в памяти.
 */
class FormalizedFileHandler(parent: ActorRef, implicit val dal: Dal, jwt: Jwt, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  var fileBuilder = ByteString.empty
  val fileStore = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[Any] = {
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
class InformalFileHandler(parent: ActorRef, dal: Dal, jwt: Jwt, meta: Meta)
  extends FileHandler(parent, dal, jwt, meta) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt, fileId: UUID): Future[Any] = {
    context unwatch fileServerConnector
    fileServerConnector ! Finish
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

