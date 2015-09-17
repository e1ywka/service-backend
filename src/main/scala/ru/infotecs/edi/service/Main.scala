/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{Props, ActorSystem}
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

  IO(Http) ! Http.Bind(handler, interface = settings.BindHost, port = settings.BindPort)

  sys.addShutdownHook {
    system.shutdown()
    dal.database.close()
  }
}
