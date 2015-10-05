/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.ActorRef
import akka.actor.Status.Failure
import akka.testkit.TestActor.{AutoPilot, KeepRunning}
import akka.testkit.TestProbe
import org.scalatest.{FlatSpec, Matchers}
import ru.infotecs.edi.service.FileUploading.AuthFileChunk
import ru.infotecs.edi.service.Parser.{InvalidSender, ParserException}
import spray.http._
import spray.testkit.ScalatestRouteTest


class UploadServiceSpec extends FlatSpec with Matchers with ScalatestRouteTest with UploadService {
  def actorRefFactory = system

  val jwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsInBpZCI6IiIsImNpZCI6IiJ9."

  "Valid request" should "return 200" in {
    val fileUpload = TestProbe()
    fileUpload.setAutoPilot(new AutoPilot {
      override def run(sender: ActorRef, msg: Any): AutoPilot = {
        msg match {
          case a: AuthFileChunk => sender ! InformalDocument("fileId", "cf.xml", "text/xml"); KeepRunning
        }
      }
    })

    Post("/upload", MultipartFormData(Map(
      "chunk" -> BodyPart(HttpEntity("0")),
      "chunks" -> BodyPart(HttpEntity("1")),
      "name" -> BodyPart(HttpEntity("cf.xml")),
      "mediaType" -> BodyPart(HttpEntity("text/xml")),
      "sha256hash" -> BodyPart(HttpEntity("this_is_hash")),
      "size" -> BodyPart(HttpEntity("123")),
      "file" -> BodyPart(HttpEntity("<file></file>"))
    )
    )).withHeaders(HttpHeaders.Cookie(HttpCookie("rememberme", jwt)) :: Nil) ~>
      uploadServiceRoute(fileUpload.ref) ~> check {
      status === StatusCodes.OK
    }
  }

  "Request to upload" should "contain rememberme cookie" in {

    val fileUpload = TestProbe()
    Post("/upload", MultipartFormData(Map(
      "chunk" -> BodyPart(HttpEntity("0")),
      "chunks" -> BodyPart(HttpEntity("2")),
      "name" -> BodyPart(HttpEntity("cf.xml")),
      "mediaType" -> BodyPart(HttpEntity("text/xml")),
      "sha256hash" -> BodyPart(HttpEntity("this_is_hash")),
      "size" -> BodyPart(HttpEntity("123"))
    ))
    ) ~> sealRoute(uploadServiceRoute(fileUpload.ref)) ~> check {
      status === StatusCodes.BadRequest
      responseAs[String] should be("Request is missing required cookie 'rememberme'")
    }
  }

  "ParserException" should "be converted to 400 (bad request)" in {
    import ru.infotecs.edi.service.errorMessageUnmarshaller._

    val fileUpload = TestProbe()
    fileUpload.setAutoPilot(new AutoPilot {
      override def run(sender: ActorRef, msg: Any): AutoPilot = {
        msg match {
          case a: AuthFileChunk => sender ! Failure(ParserException(InvalidSender)); KeepRunning
        }
      }
    })

    Post("/upload", MultipartFormData(Map(
      "chunk" -> BodyPart(HttpEntity("0")),
      "chunks" -> BodyPart(HttpEntity("2")),
      "name" -> BodyPart(HttpEntity("cf.xml")),
      "mediaType" -> BodyPart(HttpEntity("text/xml")),
      "sha256hash" -> BodyPart(HttpEntity("this_is_hash")),
      "size" -> BodyPart(HttpEntity("123")),
      "file" -> BodyPart(HttpEntity("<file></file>"))
    ))
    ).withHeaders(HttpHeaders.Cookie(HttpCookie("rememberme", jwt)) :: Nil) ~>
      sealRoute(uploadServiceRoute(fileUpload.ref)) ~>
      check {
        status === StatusCodes.BadRequest
        responseAs[String] should include("parser.invalidSender")
      }
  }
}
