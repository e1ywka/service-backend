/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import org.scalatest._
import ru.infotecs.edi.db.{Company, Friendship, H2Dal, Person}
import ru.infotecs.edi.security._
import ru.infotecs.edi.service.FileHandler.Init
import ru.infotecs.edi.service.FileUploading._
import slick.driver.H2Driver.api._
import spray.http.{BodyPart, HttpEntity}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class FileHandlerSpec(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem())
  // emulate FileServerClient
  val client = system.actorOf(Props(new Actor {
    def receive = {
      case FileServerMessage(_, _, _, _, _) => sender ! FileServerClient.Ok
    }
  }), "fileServerClient"
  )

  val dal = H2Dal("filehandlerspec")
  val ddl = dal.companies.schema ++ dal.friendships.schema ++ dal.fileInfos.schema ++ dal.persons.schema

  implicit val timeout = Timeout(1 second)

  val message = "<entity></entity>"
  val messageStream = ByteString.fromString(message)

  var jwt: JsonWebToken = _
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
    jwt = ValidJsonWebToken("",
      Jws(Alg.none, ""),
      Jwt(None, None, None, None, None, None, None, None, personId.toString, companyId.toString),
      None)
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "FormalizedFileHandler" must {

    "become handling on Init message" in {
      val totalChunks = 2
      val f = AuthFileChunk(FileChunk((0, totalChunks), BodyPart(message, "file"), Meta("fileName", 17, "123")), jwt)
      val actorRef = system.actorOf(Props(classOf[FormalizedFileHandler], self, dal, jwt, f.fileChunk.meta))
      actorRef ! Init(totalChunks)
      actorRef ! f
      expectMsg(UnparsedDocumentPart)
    }

    "respond with document model" in {
      val totalChunks = 1
      val file = new File(getClass.getResource("cf.xml").toURI)
      val f = AuthFileChunk(FileChunk((0, totalChunks), BodyPart(file, "cf.xml"), Meta("cf.xml", 1000, "123")), jwt)
      val actorRef = system.actorOf(Props(classOf[FormalizedFileHandler], self, dal, jwt, f.fileChunk.meta))
      val deathPactWatch = TestProbe()
      deathPactWatch.watch(actorRef)
      actorRef ! Init(totalChunks)
      actorRef ! f
      expectMsgClass(classOf[FormalDocument])
      deathPactWatch.expectTerminated(actorRef)
    }

    "respect ordering of chunks" in {
      val totalChunks = 10
      val filePath = Paths.get(getClass.getResource("cf.xml").toURI)

      val fileChunks = splitFile(filePath, totalChunks).zipWithIndex.map(part => {
        AuthFileChunk(FileChunk((part._2, totalChunks), BodyPart(HttpEntity(part._1)), Meta("cf.xml", part._1.length, "123")), jwt)
      }).toList

      fileChunks.size should be(totalChunks)
      val actorRef = system.actorOf(Props(classOf[FormalizedFileHandler], self, dal, jwt, fileChunks.head.fileChunk.meta))
      val deathPactWatch = TestProbe()
      deathPactWatch.watch(actorRef)
      actorRef ! Init(totalChunks)
      Random.shuffle(fileChunks).foreach(f => actorRef ! f)
      receiveN(9)
      expectMsgClass(classOf[FormalDocument])
      deathPactWatch.expectTerminated(actorRef)
    }
  }

  def splitFile(filePath: Path, splitInParts: Int): Iterator[Array[Byte]] = {
    val content = Files.readAllBytes(filePath)
    val groupSize = (content.length.doubleValue / splitInParts).intValue + 1
    content.grouped(groupSize)
  }
}
