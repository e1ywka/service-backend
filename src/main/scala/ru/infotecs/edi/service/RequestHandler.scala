/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.Actor
import akka.actor.Actor.Receive
import spray.can.Http
import spray.http._
import HttpMethods._

case object ServiceStatus

class RequestHandler extends Actor {
  def receive: Receive = {
    case _: Http.Connected => sender ! Http.Register(self)

    case HttpRequest(GET, Uri.Path("/status"), _, _, _) => {
      val asender = sender
      val fileServer
    }
  }
}
