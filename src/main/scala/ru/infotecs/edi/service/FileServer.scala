package ru.infotecs.edi.service

import akka.actor.{ActorRef, Stash, Props, Actor}
import akka.actor.Actor.Receive
import akka.pattern._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag

case class FilePart(fileId: String, chunk: Array[Byte])
case class AuthorizedFilePart(token: String, filePart: FilePart)

class FileServer(authServer: ActorRef) extends Actor with Stash {

  import context._

  val fileServerClient = actorOf(Props[FileServerClient])
  val circuitBreaker = new CircuitBreaker(context.system.scheduler, 3, 10 seconds, 1 minute)

  circuitBreaker.onOpen {
    self ! "Opened"
  }

  circuitBreaker.onClose {
    self ! "Closed"
  }

  def serviceAvailable: Receive = {
    case "Opened" => become(serviceUnavailable)
    case ServiceStatus => "Available"
    case m: FilePart => {
      val message = m
      (authServer ? "token").mapTo[String].map(token => AuthorizedFilePart(token, message)).pipeTo(self)
    }

    case m@AuthorizedFilePart(token, filePart) => {
      val message = m
      val sendFuture = circuitBreaker.withCircuitBreaker {
        fileServerClient ? m
      }
      sendFuture.onFailure {
        case _ => self ! message
      }
    }
  }

  def serviceUnavailable: Receive = {
    case "Opened" =>
    case "Closed" => {
      become(serviceAvailable)
      unstashAll()
    }
    case ServiceStatus => "Unavailable"
    case FilePart => stash()
  }

  def receive: Receive = serviceAvailable
}

class FileServerClient extends Actor {
  def receive: Actor.Receive = ???
}
