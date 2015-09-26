/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.nio.file.{Files, Paths}
import java.util.UUID

import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import ru.infotecs.edi.SettingsImpl
import ru.infotecs.edi.db.{Company, Friendship, H2Dal, Person}
import slick.driver.H2Driver.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class ParserSpec extends FlatSpec with Matchers
with BeforeAndAfter with BeforeAndAfterAll with TableDrivenPropertyChecks {

  implicit val settings = new SettingsImpl(ConfigFactory.load())
  implicit val dal = H2Dal("ParserSpec")
  val ddl = dal.fileInfos.schema ++ dal.companies.schema ++ dal.friendships.schema ++ dal.persons.schema

  override protected def afterAll(): Unit = {
    dal.database.close()
  }

  def companies: Future[(UUID, UUID, UUID)] = {
    val cid1 = UUID.randomUUID()
    val cid2 = UUID.randomUUID()
    val pid = UUID.randomUUID()
    for {
      schema <- dal.database.run(ddl.create)
      f <- dal.database.run(DBIO.seq(
        dal.companies ++= Seq (
          Company(cid1, "0100000000", Some("010000000"), false, None),
          Company(cid2, "0200000000", Some("010000000"), false, None)
        ),
        dal.friendships += Friendship(UUID.randomUUID(), cid1, cid2, "ACCEPTED"),
        dal.persons += Person(pid, "LastName", "FirstName", Some("MiddleName")))
      )
    } yield (pid, cid1, cid2)
  }

  after {
    dal.database.run(ddl.drop)
  }

  val validDocuments = Table("fileName",
    "cf.xml"
  )

  forAll(validDocuments) { (fileName: String) =>
    val b: ByteString = ByteString.fromArray(Files.readAllBytes(Paths.get(getClass.getResource(fileName).toURI)))
    val clientDocument = for {
      (pid, cid1, cid2) <- companies
      xml <- Parser.read(b)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, cid1)
      xml <- Parser.modify(UUID.randomUUID(), pid, cid1, Some(cid2), xml)
    } yield xml
    Await.ready(clientDocument, Duration(10, "seconds"))
    clientDocument.value match {
      case Some(Success(_)) => // test pass
      case Some(Failure(e)) => fail(e)
      case None => fail("No result")
    }
  }
}
