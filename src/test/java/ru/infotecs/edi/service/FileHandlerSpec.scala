/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestActorRef}
import akka.pattern.ask
import akka.util.Timeout
import org.scalatest._
import ru.infotecs.edi.service.FileHandler.Init
import ru.infotecs.edi.service.FileUploading.{UploadFinished, FileChunkUploaded, FileChunk}
import spray.http.BodyPart

import scala.concurrent.duration._
import scala.util.Success

class FileHandlerSpec(_system: ActorSystem) extends TestKit(_system)
    with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem())

  implicit val timeout = Timeout(1 second)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "BufferingFileHandler" must {
    "become handling on Init message" in {
      val totalChunks = 2
      val actorRef = system.actorOf(Props.create(classOf[BufferingFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((0, totalChunks), BodyPart("<entity></entity>", "file"), "fileName")
      expectMsg(FileChunkUploaded)
    }

    "respond with UploadFinished" in {
      val totalChunks = 1
      val actorRef = system.actorOf(Props.create(classOf[BufferingFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((0, totalChunks), BodyPart("<entity></entity>", "file"), "fileName")
      expectMsg(FileChunkUploaded)
      expectMsg(UploadFinished("fileName", true))
    }

    "respect ordering of chunks" in {

    }
  }

}
