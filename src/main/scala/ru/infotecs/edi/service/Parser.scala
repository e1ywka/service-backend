/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{ActorRef, Actor}
import akka.util.ByteString
import ru.infotecs.edi.service.Parser.{Parsing, ValidXml}

import scala.concurrent.Future

object Parser {
  case object Parsing
  case object ValidXml
  case class InvalidXml(message: String)
}

class Parser extends Actor {

  implicit val ec = context.dispatcher

  var file: ByteString = _

  def receive: Receive = {
    case f: ByteString => {
      file = f
      Future {
        println(file.decodeString("UTF-8"))
      } recoverWith {
        case e: Exception => println(e.getMessage); Future.failed(e)
      } foreach (_ => {
        context become {
          case _ => sender ! ValidXml
        }
      })
      context become {
        case _ => sender ! Parsing
      }
    }
  }
}
