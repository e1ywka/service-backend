/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.pattern._
import akka.io.IO
import akka.util.Timeout
import ru.infotecs.edi.Settings
import ru.infotecs.edi.db.PostgresDal
import spray.can.Http

import scala.concurrent.Await
import scala.concurrent.duration._

object Main extends App {

  implicit val system = ActorSystem()

  val dal = PostgresDal()
  val settings = Settings(system)

  val fileUploading = system.actorOf(Props(classOf[FileUploading], dal))
  val handler = system.actorOf(Props(classOf[UploadService], fileUploading))

  system.actorOf(Props(classOf[FileServerClientWatch]))

  val http = system.actorOf(Props(classOf[GracefulHttpShutdown]))

  http ! Http.Bind(handler, interface = settings.BindHost, port = settings.BindPort)

  sys.addShutdownHook {
    val unbindFuture = http.ask(Http.Unbind)(1 minute)
    Await.ready(unbindFuture, Duration.Inf)
    system.shutdown()
    dal.database.close()
  }

  class FileServerClientWatch extends Actor with ActorLogging {

    val fileServerClient =
      context.system.actorOf(Props(classOf[FileServerClient], IO(Http)), "fileServerClient")

    context.watch(fileServerClient)

    def receive: Receive = {
      case Terminated(a) if a.equals(fileServerClient) => {
        log.error("FileServerClient terminated. Shutdown system.")
        context.system.shutdown()
      }
    }

    override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
      case _ => Restart
    }
  }

  class GracefulHttpShutdown extends Actor {
    var httpListener: ActorRef = _
    implicit val timeout = Timeout(1 minute)
    implicit val ec = context.system.dispatcher

    def receive = {
      case bind: Http.Bind => {
        IO(Http) ! bind
        context.become {
          case Http.Bound(_) => {
            httpListener = sender()
            context.watch(httpListener)
            context.become {
              case Http.Unbind =>
                (httpListener ? Http.Unbind) pipeTo sender() foreach(_ => {
                  context.unwatch(httpListener)
                })
              case Terminated(a) if a.equals(httpListener) => context.system.shutdown()
            }
          }
        }
      }
    }
  }
}
