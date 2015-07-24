/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import spray.can.Http


object Main extends App {

  implicit val system = ActorSystem()
  val handler = system.actorOf(Props[RequestHandler])

  IO(Http) ! Http.Bind(handler, interface = "localhost", port = 10000)
}
