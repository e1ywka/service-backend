package ru.infotecs.edi.service

import akka.actor._
import akka.io.IO
import akka.io.Tcp.Connected
import akka.pattern._
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.service.FileServerClient.Finish
import spray.can.Http
import spray.http._
import spray.http.HttpMethods._

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object FileServer {

  case object UploadSucceed

  case object UploadFailed

  case class FilePart(fileId: String, chunk: Array[Byte])

  case class AuthorizedFilePart(token: String, filePart: FilePart)

}

/**
 * FileServer handles file chunks and transmits them to File Store.
 * There are available, unavailable and staging states.
 */
// TODO add staging state to check File Store is available
class FileServer extends Actor with Stash {

  import context._

  implicit val timeout = Timeout(10 seconds)

  val fileServerClient = actorOf(Props[FileServerClient])

  def available: Receive = {
    case ServiceStatus => 'Available

    case m@FileServer.AuthorizedFilePart(token, filePart) => {
      val s = sender
      (fileServerClient ? m).onComplete {
        case Success(v) => s ! FileServer.UploadSucceed
        case Failure(e: CircuitBreakerOpenException) => {
          s ! FileServer.UploadFailed
          become(unavailable) // CB is open so we stop sending files
        }
      }
    }
  }

  def unavailable: Receive = {
    case ServiceStatus => 'Unavailable
    case FileServer.FilePart => stash()
  }

  def receive: Receive = available
}

/**
 * File store client. Implements specific chunk transfer.
 * Under hood uses spray client.
 */
// TODO prestart: establish connection to File Store and notify parent
// TODO implement client as broker to distribute sending in several tcp connections and/or several nodes
object FileServerClient {
  case object Finish
}

class FileServerClient extends Actor {
  import spray.http.HttpHeaders._
  import spray.http.ContentTypes.`application/octet-stream`
  import context._

  val circuitBreaker = new CircuitBreaker(system.scheduler, 3, 10 seconds, 1 minute)

  def connected(connector: ActorRef): Receive = {
    case chunk: ByteString => connector ! MessageChunk(HttpData(chunk))
    case Finish => {
      connector ! ChunkedMessageEnd
      connector ! Http.Close
      unwatch(connector)
      become(stopping)
    }
    case Terminated(a) if a.equals(connector) => stop(self)
  }

  def stopping: Receive = {
    case Http.Closed => stop(self)
  }

  def receive: Receive = {
    case Http.Connected => {
      watch(sender)
      sender ! ChunkedRequestStart(HttpRequest(method = POST,
        uri = Uri("/edi/account/debug/upload"),
        headers = `Content-Type`(`application/octet-stream`) ::
          Authorization(GenericHttpCredentials("Bearer", "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJwaWQiOiIwZWNlMzBjMy00NzJhLTRiMTUtOGE4ZC0zNTUwMjEzMTlmODgiLCJjaWQiOiJkYzc1ZGIwZS03YWEzLTRiZTktOGU2Mi00NmNhMTU4MTVhOTciLCJpYXQiOjE0Mzg5NTA3MjMsImV4cCI6MTQzODk1NjEyMywiYXVkIjpbIkIyQiJdLCJpc3MiOiJDQSJ9.UO-nk1VrMS3F0DiElQLxZ56cHJ5pf6imKatEBoibW1E")) ::
          `Content-Disposition`("attachment", Map("filename" -> "23efbf9f-f566-4e16-b4c9-ff1427a8deb7")) ::
          RawHeader("Session-ID", "23efbf9f-f566-4e16-b4c9-ff1427a8deb7") ::
          Nil))
      become(connected(sender))
    }
    case Http.CommandFailed => stop(self)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    IO(Http) ! Http.Connect(host = "127.0.0.1", port = 9080)
  }
}
