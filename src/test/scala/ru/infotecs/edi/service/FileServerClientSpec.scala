/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.IOException
import java.util.UUID

import akka.actor.{ActorSystem, Props, Status}
import akka.io.Tcp
import akka.pattern.CircuitBreakerOpenException
import akka.testkit._
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.infotecs.edi.Settings
import ru.infotecs.edi.security.JsonWebToken
import spray.can.Http
import spray.http.{HttpEntity, HttpRequest, HttpResponse}

import scala.concurrent.duration._

class FileServerClientSpec(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with WordSpecLike with BeforeAndAfterAll with Matchers {

  def this() = this(ActorSystem("FileServerClientSpec"))
  val settings = Settings(_system)

  val jwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsInBpZCI6IiIsImNpZCI6IiJ9."

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "FileServerClient" should {
    "establish connection on preStart" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self))
      expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))
    }

    "become connected after receiving Http.HostConnectorInfo" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self))
      val setup = expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))
      fileServerClient ! 'Status
      expectMsg('Connecting)
      fileServerClient ! Http.HostConnectorInfo(self, setup)
      fileServerClient ! 'Status
      expectMsg('Connected)
    }

    "stop itself when unable to connect" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self))
      watch(fileServerClient)
      val setup = expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))
      fileServerClient ! 'Status
      expectMsg('Connecting)
      fileServerClient ! Http.CommandFailed(Tcp.Abort)
      expectTerminated(fileServerClient)
    }

    "stash FileServerMessageS when connecting" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self))
      val setup = expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))

      fileServerClient ! FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID())
      fileServerClient ! Http.HostConnectorInfo(self, setup)
      // stashed message is sent
      val request = expectMsgClass(classOf[HttpRequest])
      request.headers should not be(empty)
    }

    "forward response to sender" in {
      val io = TestProbe()
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], io.ref))
      val setup = io.expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))

      io.reply(Http.HostConnectorInfo(io.ref, setup))

      val client = TestProbe()
      client.send(fileServerClient, FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID()))
      // stashed message is sent
      io.expectMsgClass(classOf[HttpRequest])
      io.reply(HttpResponse(status = 200, entity = HttpEntity.Empty))
      client.expectMsg(FileServerClient.Ok)
    }

    "open circuit breaker on failures" in {
      val io = TestProbe()
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], io.ref))
      val setup = io.expectMsg(Http.HostConnectorSetup(settings.FileServerHost, settings.FileServerPort))
      io.reply(Http.HostConnectorInfo(io.ref, setup))

      val client = TestProbe()
      1 to 3 foreach {_ =>
        client.send(fileServerClient, FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID()))
      }
      1 to 3 foreach { _ =>
        io.receiveOne(100.millisecond.dilated)
        io.reply(Status.Failure(new IOException))
      }
      client.receiveN(3)
      client.send(fileServerClient, FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID()))
      client.expectMsgPF() {
        case s@Status.Failure(e: CircuitBreakerOpenException) => s
      }
    }
  }
}
