/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.service

import java.nio.file.{Files, Paths}
import java.util.UUID

import akka.util.ByteString
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import ru.infotecs.edi.db.{Friendship, Company, H2Dal}
import slick.driver.H2Driver.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class ParserSpec extends FlatSpec with Matchers
with BeforeAndAfter with BeforeAndAfterAll with TableDrivenPropertyChecks {

  implicit val dal = H2Dal("h2mem1")
  val ddl = dal.fileInfos.schema ++ dal.companies.schema ++ dal.friendships.schema

  override protected def afterAll(): Unit = {
    dal.database.close()
  }

  def companies: Future[(UUID, UUID)] = {
    val cid1 = UUID.randomUUID()
    val cid2 = UUID.randomUUID()
    for {
      schema <- dal.database.run(ddl.create)
      f <- dal.database.run(DBIO.seq(
        dal.companies ++= Seq (
          Company(cid1, "0100000000", Some("010000000")),
          Company(cid2, "0200000000", Some("010000000"))
        ),
      dal.friendships += Friendship(UUID.randomUUID(), cid1, cid2, "ACCEPTED")
      ))
    } yield (cid1, cid2)
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
      (cid1, cid2) <- companies
      xml <- Parser.read(b)
      (converted, xml) <- Parser.convert(xml)
      (recipient, xml) <- Parser.checkRequisites(xml, cid1)
      xml <- Parser.modify(xml)
    } yield xml
    Await.ready(clientDocument, Duration(10, "seconds"))
    clientDocument.value match {
      case Some(Success(_)) => // test pass
      case Some(Failure(e)) => fail(e)
      case None => fail("No result")
    }
  }
}
