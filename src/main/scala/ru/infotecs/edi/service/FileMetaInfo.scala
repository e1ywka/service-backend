/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.util.UUID

import ru.infotecs.edi.db.{Dal, FileInfo}
import ru.infotecs.edi.security.Jwt
import ru.infotecs.edi.service.FileUploading.Meta

import scala.concurrent.{ExecutionContext, Future}

object FileMetaInfo {

  def saveFileMeta(fileId: UUID, dal: Dal, jwt: Jwt, fileName: String, size: Long, sha256Hash: Array[Byte])(implicit executionContext: ExecutionContext): Future[UUID] = {
    import dal._
    import dal.driver.api._
    val fileInfo = FileInfo(fileId, UUID.fromString(jwt.pid), fileName, size, sha256Hash)
    withCircuitBreaker {
      database.run(DBIO.seq(
        fileInfos += fileInfo
      ))
    } map(_ => fileInfo.id)
  }

  def saveFileMeta(fileId: UUID, dal: Dal, jwt: Jwt, meta: Meta)(implicit executionContext: ExecutionContext): Future[UUID] = {
    val hash = meta.sha256Hash.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
    saveFileMeta(fileId, dal, jwt, meta.fileName, meta.size, hash)
  }
}
