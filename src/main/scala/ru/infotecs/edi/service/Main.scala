/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.ActorSystem
import akka.io.IO
import spray.can.Http


object Main extends App {

  implicit val system = ActorSystem()

  IO(Http) ! Http.Bind()
}
