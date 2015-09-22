/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import akka.actor.{Actor, Status}
import net.iharder.Base64
import ru.infotecs.edi.db.{Dal, FileInfo}
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileUploading.{FileChunk, Meta}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object FileMetaInfo {

  def saveFileMeta(dal: Dal, jwt: Jwt, meta: Meta)(implicit executionContext: ExecutionContext): Future[UUID] = {
    import dal._
    import dal.driver.api._
    val hash = meta.sha256Hash.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
    val fileInfo = FileInfo(UUID.randomUUID(), UUID.fromString(jwt.pid), meta.fileName, meta.size, hash)
    withCircuitBreaker {
      database.run(DBIO.seq(
        fileInfos += fileInfo
      ))
    } map(_ => fileInfo.id)
  }
}
