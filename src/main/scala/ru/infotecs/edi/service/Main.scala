/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.io.IO
import ru.infotecs.edi.Settings
import ru.infotecs.edi.db.PostgresDal
import spray.can.Http


object Main extends App {

  implicit val system = ActorSystem()

  val dal = PostgresDal()
  val settings = Settings(system)

  val fileUploading = system.actorOf(Props(classOf[FileUploading], dal))
  val handler = system.actorOf(Props(classOf[UploadService], fileUploading))

  system.actorOf(Props(classOf[FileServerClientWatch]))

  IO(Http) ! Http.Bind(handler, interface = settings.BindHost, port = settings.BindPort)

  sys.addShutdownHook {
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
}
