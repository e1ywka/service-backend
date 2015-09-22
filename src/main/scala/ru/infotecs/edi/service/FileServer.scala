package ru.infotecs.edi.service

import java.io.{BufferedOutputStream, File, IOException, OutputStream}
import java.nio.file.{Files, StandardOpenOption}

import akka.actor._
import akka.pattern._
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.Settings
import ru.infotecs.edi.service.FileServerClient.Ok
import spray.can.Http
import spray.http._

import scala.concurrent.duration._

/**
 * File store client. Implements specific chunk transfer.
 * Under hood uses spray client.
 */
// TODO prestart: establish connection to File Store and notify parent
// TODO implement client as broker to distribute sending in several tcp connections and/or several nodes
object FileServerClient {
  case object Ok

}

case class FileServerClientException(message: String, cause: Throwable)
  extends Exception(message, cause) {

  def this(message: String) = {
    this(message, null)
  }
}

class FileServerClient(io: ActorRef) extends ActorLogging with Stash {
  import context._
  import spray.httpx.RequestBuilding.Post

  val settings = Settings(system)
  implicit val timeout = Timeout(3 seconds)

  val circuitBreaker = new CircuitBreaker(system.scheduler, 3, 10 seconds, 1 minute)

  def connected(connector: ActorRef): Receive = {
    case 'Status => sender() ! 'Connected
    case message: FileServerMessage => {
      log.debug("send message")
      val recipient = sender()
      circuitBreaker.withCircuitBreaker {
        connector ? Post(settings.FileServerUploadUrl, message)
      } map {
        case HttpResponse(StatusCodes.Success(_), _, _, _) => Ok
        case HttpResponse(status, entity, _, _) =>
          Status.Failure(new FileServerClientException(s"Request failed: $status, $entity"))
      } pipeTo recipient
    }

    case Terminated(a) if a.equals(connector) => become(closed)
    case Http.PeerClosed | Http.ErrorClosed => become(closed)
  }

  def stopping: Receive = {
    case 'Status => sender() ! 'Stopping
    case Http.Closed | Http.ClosedAll | Http.CommandFailed(_) => stop(self)
    case _: FileServerMessage => sender() ! Status.Failure(new FileServerClientException("FileServerClient is stopping"))
  }

  def connecting: Receive = {
    case 'Status => sender() ! 'Connecting
    case Http.HostConnectorInfo(hostConnector, _) => {
      log.debug("connected")
      watch(hostConnector)
      become(connected(hostConnector))
      unstashAll()
    }
    case _: FileServerMessage => stash(); log.debug("stash message")
    case Http.CommandFailed(_) => stop(self)
  }

  def closed: Receive = {
    case 'Status => sender() ! 'Closed
    case _: FileServerMessage => {
      stash()
      io ! Http.HostConnectorSetup(host = settings.FileServerHost, port = settings.FileServerPort)
      become(connecting)
    }
  }

  def receive: Receive = closed

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    io ! Http.HostConnectorSetup(host = settings.FileServerHost, port = settings.FileServerPort)
    become(connecting)
  }
}

class DiskSave(fileName: String) extends Actor {
  import context._
  var tempFile: File = _

  def receive: Receive = {
    case bs: ByteString => {
      var fileOS: Option[OutputStream] = None

      try {
        val os = new BufferedOutputStream(Files.newOutputStream(tempFile.toPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND))
        fileOS = Some(os)
        val arr = bs.toArray
        os.write(arr)
      } catch {
        case e: IOException => stop(self)
      } finally {
        fileOS foreach(os => os.close())
      }
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    tempFile = File.createTempFile(fileName, ".upl")
  }
}
