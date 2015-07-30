/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{ActorRef, Actor}

object Parser {
  case object ValidXml
  case class InvalidXml(message: String)
}

class Parser(id: String, fileName: String, fileHandler: ActorRef) extends Actor {
  val idle: Receive = {

  }

  def receive: Receive = ???
}
