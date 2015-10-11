/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.cluster.Cluster
import akka.io.IO
import akka.pattern._
import akka.util.Timeout
import ru.infotecs.edi.Settings
import ru.infotecs.edi.db.PostgresDal
import spray.can.Http

import scala.concurrent.Await
import scala.concurrent.duration._

object Main extends App {

  implicit val system = ActorSystem("UploadService")
  val cluster = Cluster(system)

  val settings = Settings(system)
  var http: ActorRef = ActorRef.noSender
  val dal = PostgresDal()

  cluster.registerOnMemberUp {

    val fileUploading = system.actorOf(Props(classOf[FileUploading], dal))
    val handler = system.actorOf(Props(classOf[UploadServiceActor], fileUploading))

    system.actorOf(Props(classOf[FileServerClientWatch]))

    http = system.actorOf(Props(classOf[GracefulHttpShutdown]))

    http ! Http.Bind(handler, interface = settings.BindHost, port = settings.BindPort)
  }

  cluster.registerOnMemberRemoved {
    implicit val ex = system.dispatcher
    val systemShutdown = for {
      unbind <- http.ask(Http.Unbind)(1 minute)
      dbShutdown <- dal.database.shutdown
      terminated <- system.terminate()
    } yield {
        terminated
      }
    Await.ready(systemShutdown, 1 minute)
    System.exit(0)
  }

  /*sys.addShutdownHook {

  }*/

  class FileServerClientWatch extends Actor with ActorLogging {

    val fileServerClient =
      context.system.actorOf(Props(classOf[FileServerClient], IO(Http)), "fileServerClient")

    context.watch(fileServerClient)

    def receive: Receive = {
      case Terminated(a) if a.equals(fileServerClient) => {
        log.error("FileServerClient terminated. Shutdown system.")
        context.system.terminate()
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
              case Terminated(a) if a.equals(httpListener) => context.system.terminate()
            }
          }
        }
      }
    }
  }
}
