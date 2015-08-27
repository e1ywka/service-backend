package ru.infotecs.edi.service

import java.io.ByteArrayOutputStream
import java.util.UUID

import akka.actor.SupervisorStrategy.Resume
import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.db.{Dal, FileInfo}
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileServerClient.Finish
import ru.infotecs.edi.service.FileUploading._
import ru.infotecs.edi.xml.documents.clientDocuments.{AbstractCorrectiveInvoice, AbstractInvoice}
import ru.infotecs.edi.xml.documents.exceptions.ParseDocumentException
import spray.http._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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


  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5) {
    case _ => Resume
  }

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
      authFileChunk.fileChunk.file.entity match {
        case HttpEntity.NonEmpty(ContentType(`text/xml`, _), _) => actorOf(bufferingFileHandler(fileId))
        case _ => actorOf(redirectFileHandler(fileId))
      }
    }
    watch(actor)
    actor ! FileHandler.Init(authFileChunk.fileChunk.chunkOrder._2)
    actor
  }
}

