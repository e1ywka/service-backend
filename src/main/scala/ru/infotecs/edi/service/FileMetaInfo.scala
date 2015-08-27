/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.actor.{Actor, Status}
import ru.infotecs.edi.db.{Dal, FileInfo}
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileUploading.{FileChunk, Meta}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object FileMetaInfo {

  def saveFileMeta(dal: Dal, jwt: Jwt, meta: Meta)(implicit executionContext: ExecutionContext): Future[UUID] = {
    import dal._
    import dal.driver.api._
    val fileInfo = FileInfo(UUID.randomUUID(), UUID.fromString(jwt.pid), meta.fileName, meta.size, meta.sha256Hash.getBytes)
    withCircuitBreaker {
      database.run(DBIO.seq(
        fileInfos += fileInfo
      ))
    } map(_ => fileInfo.id)
  }
}
