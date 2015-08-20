package ru.infotecs.edi.service

import java.io.ByteArrayOutputStream
import java.util.UUID

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.db.{FileInfo, Dal}
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileHandler.FlushTo
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading._
import ru.infotecs.edi.xml.documents.clientDocuments.ClientDocument
import spray.http.{HttpResponse, BodyPart}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object FileUploading {

  case class Meta(fileName: String, size: Long, sha256Hash: String)

  type ChunkOrder = (Int, Int)

  case class FileChunk(chunkOrder: ChunkOrder, file: BodyPart, meta: Meta)

  case class AuthFileChunk(fileChunk: FileChunk, jwt: Jwt)

  case object FileChunkUploaded

  abstract class UploadFinished(fileId: String, fileName: String, needParsing: Boolean)

  case class BufferingFinished(fileId: String, fileName: String, b: ByteString) extends UploadFinished(fileId, fileName, true)

  case class FileSavingFinished(fileId: String, fileName: String) extends UploadFinished(fileId, fileName, false)
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 *
 * @param dal database access.
 */
class FileUploading(dal: Dal) extends Actor {

  import context._

  def bufferingFileHandler(fileId: UUID) = Props.create(classOf[FormalizedFileHandler], self, fileId, dal)
  def redirectFileHandler(fileId: UUID) = Props.create(classOf[InformalFileHandler], self, fileId)

  val fileHandlers = new scala.collection.mutable.HashMap[String, ActorRef]

  implicit val timeout: Timeout = 3 seconds

  def receive: Receive = {
    case f@AuthFileChunk(FileChunk((_, chunks), _, Meta(fileName, _, _)), jwt) => {
      val handler = fileHandlers.getOrElseUpdate(fileName, createFileHandler(f))
      handler forward f
    }

    case Terminated(h) => {
      fileHandlers.retain((_, handler) => !handler.equals(h))
    }
  }

  def createFileHandler(authFileChunk: AuthFileChunk): ActorRef = {
    val fileMetaProps = Props.create(classOf[FileMetaInfo], dal, authFileChunk.jwt)
    val fileId: UUID = Await.result((actorOf(fileMetaProps) ? authFileChunk.fileChunk).mapTo[FileInfo].map(_.id), Duration.Inf)

    val actor: ActorRef = {
      if (authFileChunk.fileChunk.meta.fileName.toLowerCase.endsWith("xml")) {
        actorOf(bufferingFileHandler(fileId))
      } else {
        actorOf(redirectFileHandler(fileId))
      }
    }
    watch(actor)
    actor ! FileHandler.Init(authFileChunk.fileChunk.chunkOrder._2)
    actor
  }
}

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

  def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[UploadFinished]

  def uploaded: Receive = {
    case _: FileChunk => {
      // логировать сообщение об ошибке
      println("boo!")
    }
  }

  val handling: Receive = {
    case f@AuthFileChunk(FileChunk((chunk, chunks), filePart, Meta(fileName, _, _)), jwt) => {
      unstashAll()
      if (chunk == nextChunk) {
        nextChunk += 1
        // выполнять парсинг файла
        val recipient = sender()
        handlingUpload(f.fileChunk)
        if (nextChunk == totalChunks) {
          context become uploaded
          uploadFinishedMessage(fileName, jwt) pipeTo recipient
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

  override def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[UploadFinished] = {
    val senderCompanyId = UUID.fromString(jwt.cid)
    (for {
      xml <- Parser.read(fileBuilder)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, senderCompanyId)
      xml <- Parser.modify(xml)
    } yield xml) andThen {
      case Success(xml) => {
        val actor = context.actorOf(Props.create(classOf[DiskSave], "test"))
        val baos = new ByteArrayOutputStream()
        xml.write(baos)
        actor ! ByteString.fromArray(baos.toByteArray)
        actor ! Finish
      }
    } map {
      case xml => BufferingFinished(fileId.toString, fileName, ByteString.empty)
    }
  }

  def handlingUpload(fileChunk: FileChunk) = {
    import fileChunk._
    // выполнять парсинг файла
    fileBuilder = fileBuilder ++ file.entity.data.toByteString
  }

  override def uploaded: Receive = super.uploaded orElse {
    case FlushTo(ref) => ref ! fileBuilder
  }
}

/**
 * Передача частей файла в персистентное хранилище.
 */
class InformalFileHandler(parent: ActorRef, fileId: UUID) extends FileHandler(parent, fileId) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String, jwt: Jwt): Future[UploadFinished] = {
    context unwatch fileServerConnector
    fileServerConnector ! Finish
    Future.successful(FileSavingFinished(fileId.toString, fileName))
  }

  def handlingUpload(fileChunk: FileChunk) = {
    fileServerConnector ! fileChunk.file.entity.data.toByteString
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    context watch fileServerConnector
  }
}
