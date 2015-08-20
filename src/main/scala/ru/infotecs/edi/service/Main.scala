/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import ru.infotecs.edi.db.PostgresDal
import spray.can.Http


object Main extends App {

  implicit val system = ActorSystem()

  val dal = PostgresDal("jdbc:postgresql://localhost:5433/edi?stringtype=unspecified&user=postgres&password=postgres")

  val fileUploading = system.actorOf(Props.create(classOf[FileUploading], dal))
  val handler = system.actorOf(Props.create(classOf[UploadService], fileUploading))

  IO(Http) ! Http.Bind(handler, interface = "10.0.9.35", port = 10000)

  sys.addShutdownHook {
    system.shutdown()
    dal.database.close()
  }
}
