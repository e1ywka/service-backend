package ru.infotecs.edi.service

import akka.actor.{Stash, Actor}
import akka.actor.Actor.Receive

class FileUploading extends Actor with Stash {
  def receive: Receive = ???
}
