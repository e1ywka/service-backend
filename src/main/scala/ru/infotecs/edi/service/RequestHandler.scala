/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.Actor
import akka.actor.Actor.Receive
import spray.can.Http
import spray.http._
import HttpMethods._

import scala.collection.mutable

case object ServiceStatus

class RequestHandler extends Actor {
  import context._

  def receive: Receive = {
    case _: Http.Connected => sender ! Http.Register(self)

    case HttpRequest(GET, Uri.Path("/status"), _, _, _) => {
      sender ! HttpResponse(status = 200, entity = "Server is working")
    }

    case HttpRequest(POST, Uri.Path("/upload"), headers, HttpEntity.NonEmpty(ContentType(MediaTypes.`multipart/form-data`, None), data), _) => {

    }
  }
}
