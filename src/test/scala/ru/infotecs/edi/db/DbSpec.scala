/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import slick.driver.H2Driver.api._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class DbSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  import scala.concurrent.ExecutionContext.Implicits.global

  val dal = H2Dal("h2mem1")
  val select1query = sql"SELECT 1".as[Int].headOption

  assume(Await.result(dal.database.run(select1query), Duration.Inf).contains(1))

  override protected def afterAll(): Unit = {
    dal.database.close()
  }

  it should "insert values to FileInfo" in {
    val fileInfo: FileInfo = FileInfo(UUID.randomUUID(), UUID.randomUUID(), "name1", 1, "0X".getBytes)

    val fi = for {
      ddl <- dal.database.run(dal.fileInfos.schema.create)
      ins <- dal.database.run(
        dal.fileInfos += fileInfo
      )
      savedFileInfo <- dal.withCircuitBreaker {
        dal.database.run(dal.fileInfos.filter(_.id === fileInfo.id).result)
      }
    } yield savedFileInfo
    Await.ready(fi, Duration(1, "second"))
    fi.value match {
      case Some(Success(seq)) => assert(seq.size == 1)
      case Some(Failure(e)) => fail(e)
      case None => fail
    }
  }

  it should "insert value into Company" in {
    val company = Company(UUID.randomUUID(), "0100000000", None, false, None)

    val c = for {
      ddl <- dal.database.run(dal.companies.schema.create)
      ins <- dal.database.run(
        dal.companies += company
      )
      savedCompany <- dal.withCircuitBreaker {
        dal.database.run(dal.companies.filter(_.id === company.id).result.headOption)
      }
    } yield savedCompany
    Await.ready(c, Duration(1, "second"))
    c.value match {
      case Some(Success(seq)) => assert(seq.size == 1)
      case Some(Failure(e)) => fail(e)
      case None => fail
    }
  }
}
