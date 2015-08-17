/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

/**
 * Meta information about files.
 * @param id unique file id.
 * @param uploader user who uploaded file.
 * @param name user specified file name.
 * @param size file size.
 * @param sha256 hash computed using SHA-256.
 */
case class FileInfo(id: UUID, uploader: UUID, name: String, size: Long, sha256: Array[Byte])

/**
 * DB schema description.
 */
trait DbModel { this: DriverComponent =>
  import driver.api._

  /**
   * Table &quot;file&quot; contains meta information about uploaded files.
   */
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
