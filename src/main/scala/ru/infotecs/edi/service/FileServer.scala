package ru.infotecs.edi.service

import java.io.{BufferedOutputStream, IOException, OutputStream, File}
import java.nio.file.{StandardOpenOption, OpenOption, Files}

import akka.actor._
import akka.io.IO
import akka.pattern._
import akka.routing.RoundRobinPool
import akka.util.{ByteString, Timeout}
import ru.infotecs.edi.service.FileServerClient.Finish
import spray.can.Http
import spray.http._
import spray.httpx.marshalling._
import spray.http.HttpMethods._

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * File store client. Implements specific chunk transfer.
 * Under hood uses spray client.
 */
// TODO prestart: establish connection to File Store and notify parent
// TODO implement client as broker to distribute sending in several tcp connections and/or several nodes
object FileServerClient {
  case object Finish

  val supervisor = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5 seconds) {
    case e => SupervisorStrategy.restart
  }
  def router(host: String, port: Int) =
    RoundRobinPool(5, supervisorStrategy = supervisor)
      .props(Props(classOf[FileServerClient], host, port))
}

case class FileServerClientException(message: Option[String])
  extends Exception(message.getOrElse("Unexpected exception"))

class FileServerClient(io: ActorRef, host: String, port: Int) extends Actor with Stash {
  import context._

  implicit val timeout = Timeout(3 seconds)

  val circuitBreaker = new CircuitBreaker(system.scheduler, 3, 10 seconds, 1 minute)

  def connected(connector: ActorRef): Receive = {
    case 'Status => sender() ! 'Connected
    case message: FileServerMessage => {
      val recipient = sender()
      circuitBreaker.withCircuitBreaker {
        connector ? HttpRequest(POST, Uri("/upload"), entity = marshalUnsafe(message))
      } pipeTo recipient
    }

    case Terminated(a) if a.equals(connector) => become(closed)
    case Finish => connector ! Http.CloseAll; become(stopping)
    case Http.PeerClosed | Http.ErrorClosed => become(closed)
  }

  def stopping: Receive = {
    case 'Status => sender() ! 'Stopping
    case Http.Closed | Http.ClosedAll | Http.CommandFailed(_) => stop(self)
    case _: FileServerMessage => sender() ! Status.Failure(FileServerClientException(Some("FileServerClient is stopping")))
  }

  def connecting: Receive = {
    case 'Status => sender() ! 'Connecting
    case Http.HostConnectorInfo(hostConnector, _) => {
      watch(hostConnector)
      become(connected(hostConnector))
      unstashAll()
    }
    case _: FileServerMessage => stash()
    case Http.CommandFailed(_) => stop(self)
  }

  def closed: Receive = {
    case 'Status => sender() ! 'Closed
    case _: FileServerMessage => {
      stash()
      io ! Http.HostConnectorSetup(host = host, port = port)
      become(connecting)
    }
    case Finish => stop(self)
  }

  def receive: Receive = closed

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    io ! Http.HostConnectorSetup(host = host, port = port)
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

    case Finish => {
      stop(self)
    }
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    tempFile = File.createTempFile(fileName, ".upl")
  }
}
