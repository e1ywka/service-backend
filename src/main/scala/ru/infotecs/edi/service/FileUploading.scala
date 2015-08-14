package ru.infotecs.edi.service

import java.util.UUID

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.db.{FileInfo, Dal}
import ru.infotecs.edi.service.FileHandler.FlushTo
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading._
import spray.http.BodyPart

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object FileUploading {

  case class Meta(fileName: String, size: Long, sha256Hash: String)

  type ChunkOrder = (Int, Int)

  case class FileChunk(chunkOrder: ChunkOrder, file: BodyPart, meta: Meta)

  case object FileChunkUploaded

  abstract class UploadFinished(fileName: String, needParsing: Boolean)

  case class BufferingFinished(fileName: String, b: ByteString) extends UploadFinished(fileName, true)

  case class FileSavingFinished(fileName: String) extends UploadFinished(fileName, false)
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 */
class FileUploading(dal: Dal) extends Actor {

  import context._

  val fileMetaProps = Props.create(classOf[FileMetaInfo], dal)
  def bufferingFileHandler(fileId: UUID) = Props.create(classOf[BufferingFileHandler], self, fileId)
  def redirectFileHandler(fileId: UUID) = Props.create(classOf[RedirectFileHandler], self, fileId)

  val fileHandlers = new scala.collection.mutable.HashMap[String, ActorRef]

  implicit val timeout: Timeout = 3 seconds

  def receive: Receive = {
    case f@FileChunk((_, chunks), _, Meta(fileName, _, _)) => {
      val handler = fileHandlers.getOrElseUpdate(fileName, createFileHandler(f))
      handler forward f
    }

    /*case UploadFinished(fileName, true) => sender ! FlushTo(actorOf(Props[Parser]))
    case UploadFinished(fileName, false) =>*/

    case Terminated(h) => {
      fileHandlers.retain((_, handler) => !handler.equals(h))
      /*if (fileHandlers.isEmpty) {
        context.stop(self)
      }*/
    }
  }

  def createFileHandler(fileChunk: FileChunk): ActorRef = {

    val fileId: UUID = Await.result((actorOf(fileMetaProps) ? fileChunk).mapTo[FileInfo].map(_.id), Duration.Inf)

    val actor: ActorRef = {
      if (fileChunk.meta.fileName.toLowerCase.endsWith("xml")) {
        actorOf(bufferingFileHandler(fileId))
      } else {
        actorOf(redirectFileHandler(fileId))
      }
    }
    watch(actor)
    actor ! FileHandler.Init(fileChunk.chunkOrder._2)
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

  def uploadFinishedMessage(fileName: String): UploadFinished

  def uploaded: Receive = {
    case _: FileChunk => {
      // логировать сообщение об ошибке
      println("boo!")
    }
  }

  val handling: Receive = {
    case f@FileChunk((chunk, chunks), filePart, Meta(fileName, _, _)) => {
      unstashAll()
      if (chunk == nextChunk) {
        nextChunk += 1
        // выполнять парсинг файла
        val recipient = sender
        handlingUpload(f)
        if (nextChunk == totalChunks) {
          context become uploaded
          sender ! uploadFinishedMessage(fileName)
        } else {
          sender ! FileChunkUploaded
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
class BufferingFileHandler(parent: ActorRef, fileId: UUID) extends FileHandler(parent, fileId) {

  var fileBuilder = ByteString.empty

  override def uploadFinishedMessage(fileName: String): UploadFinished = {
    BufferingFinished(fileName, fileBuilder)
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
class RedirectFileHandler(parent: ActorRef, fileId: UUID) extends FileHandler(parent, fileId) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def uploadFinishedMessage(fileName: String): UploadFinished = {
    FileSavingFinished(fileName)
  }

  def handlingUpload(fileChunk: FileChunk) = {
    fileServerConnector ! fileChunk.file.entity.data.toByteString
    if (fileChunk.chunkOrder._1 == fileChunk.chunkOrder._2 - 1) {
      context unwatch fileServerConnector
      fileServerConnector ! Finish
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    context watch fileServerConnector
  }
}
