/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

import org.scalatest._
import slick.driver.H2Driver.api._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class CompanyModelSpec extends FlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.concurrent.ExecutionContext.Implicits.global

  val dal = H2Dal("h2mem1")
  val ddl = dal.companies.schema ++ dal.friendships.schema
  var ddlFuture: Future[Any] = Future.failed(null)
  var ddlDropFuture: Future[Any] = Future.successful(None)

  override protected def afterAll(): Unit = {
    dal.database.close()
  }

  override protected def beforeEach = {
    ddlFuture = dal.database.run(ddl.create)
  }

  override protected def afterEach = {
    ddlDropFuture = dal.database.run(ddl.drop)
  }

  "Find by id" should "query company table by id field" in {
    val company = Company(UUID.randomUUID(), "0100000000", None, false, None)

    val c = for {
      ddlDrop <- ddlDropFuture
      ddl <- ddlFuture
      ins <- dal.database.run(
        dal.companies += company
      )
      savedCompany <- dal.database.run(dal.find(company.id).result.headOption)
    } yield savedCompany
    Await.ready(c, Duration(1, "second"))
    c.value match {
      case Some(Success(gotCompany)) => gotCompany.isDefined && gotCompany.get == company
      case Some(Failure(e)) => fail(e)
      case None => fail()
    }
  }

  "Find by inn and kpp" should "query company table by inn and kpp fields" in {
    val company = Company(UUID.randomUUID(), "0100000000", None, false, None)

    val c = for {
      ddlDrop <- ddlDropFuture
      ddl <- ddlFuture
      ins <- dal.database.run(
        dal.companies += company
      )
      savedCompany <- dal.database.run(dal.find("0100000000", None).result.headOption)
    } yield savedCompany
    Await.ready(c, Duration(1, "second"))
    c.value match {
      case Some(Success(gotCompany)) => gotCompany.isDefined && gotCompany.get == company
      case Some(Failure(e)) => fail(e)
      case None => fail()
    }
  }

  "Method companiesAreFriends" should "return 'true' if two companies have b2b_friendship record" in {
    val company1 = Company(UUID.randomUUID(), "0100000000", None, false, None)
    val company2 = Company(UUID.randomUUID(), "0200000000", None, false, None)
    val friendship = Friendship(UUID.randomUUID(), company1.id, company2.id, "ACCEPTED")

    val f = for {
      ddlDrop <- ddlDropFuture
      ddl <- ddlFuture
      ins <- dal.database.run(DBIO.seq(
        dal.companies += company1,
        dal.companies += company2,
        dal.friendships += friendship
      ))
      areFriends <- dal.database.run(dal.companiesAreFriends(company1.id, company2.id).head)
    } yield areFriends
    Await.ready(f, Duration(1, "second"))
    f.value match {
      case Some(Success(true)) => // pass
      case Some(Failure(e)) => fail(e)
      case None => fail()
    }
  }
}
