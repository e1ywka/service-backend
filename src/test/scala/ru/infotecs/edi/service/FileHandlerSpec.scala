/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.File
import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import org.scalatest._
import ru.infotecs.edi.db.{Friendship, Person, Company, H2Dal}
import ru.infotecs.edi.security.{Jwt, ValidJsonWebToken, JsonWebToken}
import ru.infotecs.edi.service.FileHandler.{FlushTo, Init}
import ru.infotecs.edi.service.FileUploading._
import slick.driver.H2Driver.api._
import spray.http.BodyPart

import scala.concurrent.Await
import scala.concurrent.duration._

class FileHandlerSpec(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem())

  val dal = H2Dal("h2mem1")
  val ddl = dal.companies.schema ++ dal.friendships.schema ++ dal.fileInfos.schema ++ dal.persons.schema

  implicit val timeout = Timeout(1 second)

  val message = "<entity></entity>"
  val messageStream = ByteString.fromString(message)

  var jwt: Jwt = _
  var personId: UUID = _
  var companyId: UUID = _

  override protected def beforeAll(): Unit = {
    companyId = UUID.randomUUID()
    val friendId = UUID.randomUUID()
    personId = UUID.randomUUID()
    val prepairDb = dal.database.run(DBIO.seq(
      ddl.create,
      dal.companies += Company(companyId, "0100000000", Some("010000000"), false, None),
      dal.companies += Company(friendId, "0200000000", Some("010000000"), false, None),
      dal.friendships += Friendship(UUID.randomUUID(), companyId, friendId, "ACCEPTED"),
      dal.persons += Person(personId, "Фамилия", "Имя", Some("Отчество"))
    ))
    Await.ready(prepairDb, Duration.Inf)
    jwt = Jwt(None, None, None, None, None, None, None, None, personId.toString, companyId.toString)
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "BufferingFileHandler" must {

    "become handling on Init message" in {
      val totalChunks = 2
      val f = AuthFileChunk(FileChunk((0, totalChunks), BodyPart(message, "file"), Meta("fileName", 17, "123")), jwt)
      val actorRef = system.actorOf(Props.create(classOf[FormalizedFileHandler], self, dal, jwt, f.fileChunk.meta))
      actorRef ! Init(totalChunks)
      actorRef ! f
      expectMsg(FileChunkUploaded)
    }

    "respond with document model" in {
      val totalChunks = 1
      val file = new File(getClass.getResource("cf.xml").toURI)
      val f = AuthFileChunk(FileChunk((0, totalChunks), BodyPart(file, "cf.xml"), Meta("cf.xml", 1000, "123")), jwt)
      val actorRef = system.actorOf(Props.create(classOf[FormalizedFileHandler], self, dal, jwt, f.fileChunk.meta))
      actorRef ! Init(totalChunks)
      actorRef ! f
      expectMsgClass(classOf[FormalDocument])
    }

    /*"respect ordering of chunks" in {
      val totalChunks = 2
      val actorRef = system.actorOf(Props.create(classOf[FormalizedFileHandler], self))
      actorRef ! Init(totalChunks)
      actorRef ! FileChunk((1, totalChunks), BodyPart("</entity>", "file"), Meta("fileName", 9, "123"))
      actorRef ! FileChunk((0, totalChunks), BodyPart("<entity>", "file"), Meta("fileName", 8, "123"))
      expectMsg(FileChunkUploaded)
      expectMsgClass(classOf[FormalDocument])

      val probe = TestProbe()
      actorRef ! FlushTo(probe.ref)
      probe.expectMsgPF() {
        case b: ByteString => b.decodeString("UTF-8") should be(message)
      }
    }*/
  }

}
