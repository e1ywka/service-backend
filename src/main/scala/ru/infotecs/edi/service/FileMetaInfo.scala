/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.actor.{Actor, Status}
import ru.infotecs.edi.db.{Dal, FileInfo}
import ru.infotecs.edi.service.FileUploading.{FileChunk, Meta}

import scala.util.{Failure, Success}

class FileMetaInfo(dal: Dal) extends Actor {

  import dal._
  import dal.driver.api._

  implicit val ec = context.dispatcher

  def receive: Receive = {
    case FileChunk(_, _, Meta(name, size, sha256)) => {
      val s = sender()
      val fileInfo = FileInfo(UUID.randomUUID(), UUID.fromString("6401653c-e582-4959-9014-d36dcbfa344c"), name, size, sha256.getBytes)
      withCircuitBreaker {
        database.run(DBIO.seq(
          fileInfos += fileInfo
        ))
      } onComplete {
        case Success(_) => s ! fileInfo
        case Failure(e) => s ! Status.Failure(e)
      }

    }

  }
}
