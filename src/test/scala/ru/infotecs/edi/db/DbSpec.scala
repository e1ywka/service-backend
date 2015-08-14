/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import slick.driver.H2Driver.api._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

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

    val f = dal.database.run(dal.fileInfos.schema.create)

    for {
      ddl <- f
      id <- dal.database.run(
        dal.fileInfos.returning(dal.fileInfos.map(_.id)) += fileInfo
      )
      savedFileInfo <- dal.withCircuitBreaker {
        dal.database.run(dal.fileInfos.filter(_.id === id).result)
      }
    } yield savedFileInfo
  }
}
