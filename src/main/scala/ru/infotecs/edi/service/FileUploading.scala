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
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice, ClientDocument}
import spray.http._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object FileUploading {

  /**
   * File's metadata computed by client.
   * @param fileName user spcified file name.
   * @param size total file size.
   * @param sha256Hash SHA-256 file digest.
   */
  case class Meta(fileName: String, size: Long, sha256Hash: String)

  type ChunkOrder = (Int, Int)

  /**
   * File chunk. Contains order number of all chunks, data of that chunk, and original file metadata.
   * @param chunkOrder order number of that chunk and total chunk count.
   * @param file file chunk data.
   * @param meta original file metadata.
   */
  case class FileChunk(chunkOrder: ChunkOrder, file: BodyPart, meta: Meta)

  /**
   * Represent file chunk and authorization token sent by client.
   * @param fileChunk file chunk.
   * @param jwt authorization token.
   */
  case class AuthFileChunk(fileChunk: FileChunk, jwt: Jwt)

  /**
   * Message that is sent back to connection layer when file part successfully processed.
   */
  case object FileChunkUploaded
}

/**
 * FileUploading aggregates uploaded file chunks and performs file parsing.
 *
 * @param dal database access.
 */
class FileUploading(dal: Dal) extends Actor {
  import MediaTypes.`text/xml`
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

    case Terminated(h) =>
      fileHandlers.retain((_, handler) => !handler.equals(h))
  }

  def createFileHandler(authFileChunk: AuthFileChunk): ActorRef = {
    val fileMetaProps = Props.create(classOf[FileMetaInfo], dal, authFileChunk.jwt)
    val fileId: UUID = Await.result((actorOf(fileMetaProps) ? authFileChunk.fileChunk).mapTo[FileInfo].map(_.id), Duration.Inf)

    val actor: ActorRef = {
      val contentType: Option[ContentType] = authFileChunk.fileChunk.file.entity match {
        case HttpEntity.NonEmpty(c, _) => Some(c)
        case _ => None
      }
      contentType match {
        case Some(ContentType(`text/xml`, _)) => actorOf(bufferingFileHandler(fileId))
        case _ => actorOf(redirectFileHandler(fileId))
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
      }
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
