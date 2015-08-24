/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

import slick.lifted.ForeignKey

/**
 * Meta information about files.
 * @param id unique file id.
 * @param uploader user who uploaded file.
 * @param name user specified file name.
 * @param size file size.
 * @param sha256 hash computed using SHA-256.
 */
case class FileInfo(id: UUID, uploader: UUID, name: String, size: Long, sha256: Array[Byte])

case class Company(id: UUID, inn: String, kpp: Option[String], isEntrepreneur: Boolean, entrepreneurGosRegCertificate: Option[String])

case class Friendship(id: UUID, initiatorId: UUID, recipientId: UUID, status: String)

case class Person(id: UUID, lastName: String, firstName: String, middleName: Option[String])

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

  class Companies(tag: Tag) extends Table[Company](tag, "company") {
    def id = column[UUID]("contractor_uuid", O.PrimaryKey)
    def inn = column[String]("inn")
    def kpp = column[Option[String]]("kpp")
    def isEntrepreneur = column[Boolean]("entrepreneur")
    def entrepreneurGosRegCertificate = column[Option[String]]("entrepreneur_gos_reg_certificate")

    def * = (id, inn, kpp, isEntrepreneur, entrepreneurGosRegCertificate) <> (Company.tupled, Company.unapply)
  }
  val companies = TableQuery[Companies]

  class Friendships(tag: Tag) extends Table[Friendship](tag, "b2b_friendship") {
    def id = column[UUID]("b2b_friendship_uuid", O.PrimaryKey)
    def status = column[String]("status")
    def initiatorId = column[UUID]("initiator_contractor_uuid")
    def recipientId = column[UUID]("recipient_contractor_uuid")

    def initiatorFk = foreignKey("b2b_friendship_initiator_contractor_uuid_idx", initiatorId, companies)(_.id)
    def recipientFk = foreignKey("b2b_friendship_recipient_contractor_uuid_idx", recipientId, companies)(_.id)

    def * = (id, initiatorId, recipientId, status) <> (Friendship.tupled, Friendship.unapply)
  }
  val friendships = TableQuery[Friendships]

  class Persons(tag: Tag) extends Table[Person](tag, "person") {
    def id = column[UUID]("b2b_friendship_uuid", O.PrimaryKey)
    def lastName = column[String]("last_name")
    def firstName = column[String]("first_name")
    def middleName = column[Option[String]]("middle_name")

    def * = (id, lastName, firstName, middleName) <> (Person.tupled, Person.unapply)
  }
  val persons = TableQuery[Persons]
}
