/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

case class FileInfo(id: UUID, uploader: UUID, name: String, size: Long, sha256: Array[Byte])

trait DbModel { this: DriverComponent =>
  import driver.api._

  class FileInfos(tag: Tag) extends Table[FileInfo](tag, "file") {
    def id = column[UUID]("file_uuid", O.PrimaryKey)
    def uploader = column[UUID]("uploader_uuid")
    def name = column[String]("name")
    def size = column[Long]("size")
    def sha256 = column[Array[Byte]]("sha256")

    def * = (id, uploader, name, size, sha256) <> (FileInfo.tupled, FileInfo.unapply)
  }
  val fileInfos = TableQuery[FileInfos]
}
