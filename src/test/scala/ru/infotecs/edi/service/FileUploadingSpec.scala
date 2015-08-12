/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.scalatest.FlatSpec
import ru.infotecs.edi.service.FileUploading
import ru.infotecs.edi.service.FileUploading.{FileChunk, Meta}
import spray.http.BodyPart

import scala.concurrent.duration._

class FileUploadingSpec extends FlatSpec {

  implicit val system = ActorSystem()
  implicit val timeout = Timeout(1 second)

  it should "create new FileHandler for new file" in {
    val actorRef = TestActorRef[FileUploading]
    val actor = actorRef.underlyingActor
    actorRef ! FileChunk((0, 1), BodyPart("123", "file"),  Meta("fileName", 17, "123"))

    assert(actor.fileHandlers.contains("fileName"))
  }
}
