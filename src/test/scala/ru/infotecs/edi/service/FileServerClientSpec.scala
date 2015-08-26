/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.io.IOException
import java.util.UUID

import akka.actor.{Status, Props, ActorSystem}
import akka.io.Tcp
import akka.pattern.CircuitBreakerOpenException
import akka.testkit._
import akka.util.ByteString
import org.scalatest.{WordSpecLike, BeforeAndAfterAll}
import ru.infotecs.edi.security.{JsonWebToken}
import spray.can.Http
import spray.http.{HttpEntity, HttpResponse, HttpRequest}

import scala.concurrent.duration._

class FileServerClientSpec(_system: ActorSystem) extends TestKit(_system)
with ImplicitSender with WordSpecLike with BeforeAndAfterAll {

  def this() = this(ActorSystem("FileServerClientSpec"))

  val jwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsInBpZCI6IiIsImNpZCI6IiJ9."

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "FileServerClient" should {
    "establish connection on preStart" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self, "host", 0))
      expectMsg(Http.HostConnectorSetup("host", 0))
    }

    "become connected after receiving Http.HostConnectorInfo" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self, "host", 0))
      val setup = expectMsg(Http.HostConnectorSetup("host", 0))
      fileServerClient ! 'Status
      expectMsg('Connecting)
      fileServerClient ! Http.HostConnectorInfo(self, setup)
      fileServerClient ! 'Status
      expectMsg('Connected)
    }

    "stop itself when unable to connect" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self, "host", 0))
      watch(fileServerClient)
      val setup = expectMsg(Http.HostConnectorSetup("host", 0))
      fileServerClient ! 'Status
      expectMsg('Connecting)
      fileServerClient ! Http.CommandFailed(Tcp.Abort)
      expectTerminated(fileServerClient)
    }

    "stash FileServerMessageS when connecting" in {
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], self, "host", 0))
      val setup = expectMsg(Http.HostConnectorSetup("host", 0))

      fileServerClient ! FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID())
      fileServerClient ! Http.HostConnectorInfo(self, setup)
      // stashed message is sent
      expectMsgClass(classOf[HttpRequest])
    }

    "forward response to sender" in {
      val io = TestProbe()
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], io.ref, "host", 0))
      val setup = io.expectMsg(Http.HostConnectorSetup("host", 0))

      io.reply(Http.HostConnectorInfo(io.ref, setup))

      val client = TestProbe()
      client.send(fileServerClient, FileServerMessage(ByteString.empty, 0, 1, JsonWebToken(jwt), UUID.randomUUID()))
      // stashed message is sent
      io.expectMsgClass(classOf[HttpRequest])
      io.reply(HttpResponse(status = 200, entity = HttpEntity.Empty))
      client.expectMsgClass(classOf[HttpResponse])
    }

    "open circuit breaker on failures" in {
      val io = TestProbe()
      val fileServerClient = system.actorOf(Props(classOf[FileServerClient], io.ref, "host", 0))
      val setup = io.expectMsg(Http.HostConnectorSetup("host", 0))
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
