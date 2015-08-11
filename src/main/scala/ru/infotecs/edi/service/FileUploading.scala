package ru.infotecs.edi.service

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.service.FileHandler.FlushTo
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading.{FileChunk, FileChunkUploaded, UploadFinished}
import spray.http.BodyPart

import scala.concurrent.Future
import scala.concurrent.duration._

object FileUploading {

  case class FileChunk(chunkOrder: (Int, Int), file: BodyPart, fileName: String)

  case object FileChunkUploaded

  case class UploadFinished(fileName: String, needParsing: Boolean)
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 */
class FileUploading extends Actor {

  import context._

  val bufferingFileHandler = Props.create(classOf[BufferingFileHandler], self)
  val redirectFileHandler = Props.create(classOf[RedirectFileHandler], self)

  val fileHandlers = new scala.collection.mutable.HashMap[String, ActorRef]

  implicit val timeout: Timeout = 3 seconds

  def receive: Receive = {
    case f@FileChunk((_, chunks), _, fileName) => {
      val handler = fileHandlers.getOrElseUpdate(fileName, createFileHandler(fileName, chunks))
      val recipient = sender
      handler ? f pipeTo recipient
    }

    case UploadFinished(fileName, true) => sender ! FlushTo(actorOf(Props[Parser]))
    case UploadFinished(fileName, false) =>

    case Terminated(h) => {
      fileHandlers.retain((_, handler) => !handler.equals(h))
      if (fileHandlers.isEmpty) {
        context.stop(self)
      }
    }
  }

  def createFileHandler(fileName: String, chunks: Int): ActorRef = {
    val actor: ActorRef = {
      if (fileName.toLowerCase.endsWith("xml")) {
        actorOf(bufferingFileHandler)
      } else {
        actorOf(redirectFileHandler)
      }
    }
    watch(actor)
    actor ! FileHandler.Init(chunks)
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
 */
abstract sealed class FileHandler(parent: ActorRef) extends Actor with Stash {
  var nextChunk = 0
  var totalChunks = 0
  implicit val ec = context.system.dispatcher

  def handlingUpload(fileChunk: FileChunk): Future[FileChunkUploaded.type]

  def needParsing: Boolean

  def uploaded: Receive = {
    case _: FileChunk => {
      // логировать сообщение об ошибке
      println("boo!")
    }
  }

  val handling: Receive = {
    case f@FileChunk((chunk, chunks), filePart, fileName) => {
      unstashAll()
      if (chunk == nextChunk) {
        nextChunk += 1
        // выполнять парсинг файла
        val recipient = sender
        handlingUpload(f).pipeTo(recipient)
        if (nextChunk == totalChunks) {
          context become uploaded
          parent ! UploadFinished(fileName, needParsing)
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
class BufferingFileHandler(parent: ActorRef) extends FileHandler(parent: ActorRef) {

  var fileBuilder = ByteString.empty

  override def needParsing: Boolean = true

  def handlingUpload(fileChunk: FileChunk) = {
    import fileChunk._
    // выполнять парсинг файла
    fileBuilder = fileBuilder ++ file.entity.data.toByteString
    Future.successful(FileChunkUploaded)
  }

  override def uploaded: Receive = super.uploaded orElse {
    case FlushTo(ref) => ref ! fileBuilder
  }
}

/**
 * Передача частей файла в персистентное хранилище.
 */
class RedirectFileHandler(parent: ActorRef) extends FileHandler(parent: ActorRef) {

  val fileServerConnector = context.actorOf(Props.create(classOf[DiskSave], "test"))

  override def needParsing: Boolean = false

  def handlingUpload(fileChunk: FileChunk) = {
    Future {
      fileServerConnector ! fileChunk.file.entity.data.toByteString
      if (fileChunk.chunkOrder._1 == fileChunk.chunkOrder._2 - 1) {
        context unwatch fileServerConnector
        fileServerConnector ! Finish
      }
    }.map(_ => FileChunkUploaded)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    context watch fileServerConnector
  }
}
