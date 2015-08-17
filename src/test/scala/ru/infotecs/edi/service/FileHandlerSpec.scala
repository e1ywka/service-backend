/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import org.scalatest._
import ru.infotecs.edi.service.FileHandler.{FlushTo, Init}
import ru.infotecs.edi.service.FileUploading._
import spray.http.BodyPart

import scala.concurrent.duration._

class FileHandlerSpec(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem())

  implicit val timeout = Timeout(1 second)

  val message = "<entity></entity>"
  val messageStream = ByteString.fromString(message)

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "BufferingFileHandler" must {

    "become handling on Init message" in {
      val totalChunks = 2
      val actorRef = system.actorOf(Props.create(classOf[BufferingFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((0, totalChunks), BodyPart(message, "file"), Meta("fileName", 17, "123"))
      expectMsg(FileChunkUploaded)
    }

    "respond with UploadFinished" in {
      val totalChunks = 1
      val actorRef = system.actorOf(Props.create(classOf[BufferingFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((0, totalChunks), BodyPart(message, "file"), Meta("fileName", 17, "123"))
      expectMsgClass(classOf[BufferingFinished])
    }

    "respect ordering of chunks" in {
      val totalChunks = 2
      val actorRef = system.actorOf(Props.create(classOf[BufferingFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((1, totalChunks), BodyPart("</entity>", "file"), Meta("fileName", 9, "123"))
      actorRef ! FileChunk((0, totalChunks), BodyPart("<entity>", "file"), Meta("fileName", 8, "123"))
      expectMsg(FileChunkUploaded)
      expectMsgClass(classOf[BufferingFinished])

      val probe = TestProbe()
      actorRef ! FlushTo(probe.ref)
      probe.expectMsgPF() {
        case b: ByteString => b.decodeString("UTF-8") should be(message)
      }
    }
  }

}
