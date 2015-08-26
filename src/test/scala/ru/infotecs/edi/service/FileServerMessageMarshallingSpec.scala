/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.util.ByteString
import org.scalatest.{Matchers, FlatSpec}
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.httpx.marshalling._
import ru.infotecs.edi.security.{JsonWebToken}

class FileServerMessageMarshallingSpec extends FlatSpec with Matchers {

  import spray.http.MediaTypes.`application/octet-stream`

  val data = "qwerty".getBytes

  val jwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsInBpZCI6IiIsImNpZCI6IiJ9."

  "Marshaller" should "serialize message" in {
    val fileId = UUID.randomUUID()
    val message = FileServerMessage(ByteString.fromArray(data), 0, data.length, JsonWebToken(jwt), fileId)
    val marshallingContext = new CollectingMarshallingContext
    val either = marshal(message, marshallingContext)
    either match {
      case Left(_) => fail()
      case Right(entity) => entity match {
        case HttpEntity.Empty => fail()
        case HttpEntity.NonEmpty(ContentType(`application/octet-stream`, None), httpData) =>
          val headers = marshallingContext.headers
          headers should contain(RawHeader("Session-ID", fileId.toString))
          headers should contain(
            RawHeader("X-Content-Range", s"bytes 0-${data.length - 1}/${data.length}"))
      }
    }
  }

}
