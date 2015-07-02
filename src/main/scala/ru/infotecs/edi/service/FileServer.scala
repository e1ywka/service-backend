package ru.infotecs.edi.service

import akka.actor.{Stash, Props, Actor}
import akka.pattern._

import scala.concurrent.duration._
import scala.util.{Success, Failure}

case object UploadSucceed
case object UploadFailed
case class FilePart(fileId: String, chunk: Array[Byte])
case class AuthorizedFilePart(token: String, filePart: FilePart)

/**
 * FileServer handles file chunks and transmits them to File Store.
 * There are available, unavailable and staging states.
 */
// TODO add staging state to check File Store is available
class FileServer extends Actor with Stash {

  import context._

  val fileServerClient = actorOf(Props[FileServerClient])

  def available: Receive = {
    case ServiceStatus => "Available"

    case m@AuthorizedFilePart(token, filePart) => {
      val s = sender
      (fileServerClient ? m).onComplete {
        case Success => s ! UploadSucceed
        case Failure(e: CircuitBreakerOpenException) => {
          s ! UploadFailed
          become(unavailable) // CB is open so we stop sending files
        }
      }
    }
  }

  def unavailable: Receive = {
    case ServiceStatus => "Unavailable"
    case FilePart => stash()
  }

  def receive: Receive = available
}

/**
 * File store client. Implements specific chunk transfer.
 * Under hood uses spray client.
 */
// TODO prestart: establish connection to File Store and notify parent
// TODO implement client as broker to distribute sending in several tcp connections and/or several nodes
class FileServerClient extends Actor {
  val circuitBreaker = new CircuitBreaker(context.system.scheduler, 3, 10 seconds, 1 minute)

  def receive: Receive = ???
}
