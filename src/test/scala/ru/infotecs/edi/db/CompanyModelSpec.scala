/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

import org.scalatest._
import slick.driver.H2Driver.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class CompanyModelSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  import scala.concurrent.ExecutionContext.Implicits.global

  val dal = H2Dal("companymodelspec")
  val ddl = dal.companies.schema ++ dal.friendships.schema

  override protected def afterAll(): Unit = {
    dal.database.close()
  }

  "Find by id" should "query company table by id field" in {
    val company = Company(UUID.randomUUID(), "0100000000", None, false, None)

    val c = for {
      ddlCreate <- dal.database.run(ddl.create)
      ins <- dal.database.run(
        dal.companies += company
      )
      savedCompany <- dal.database.run(dal.find(company.id).result.headOption)
      ddlDrop <- dal.database.run(ddl.drop)
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
      ddlCreate <- dal.database.run(ddl.create)
      ins <- dal.database.run(
        dal.companies += company
      )
      savedCompany <- dal.database.run(dal.find("0100000000", None).result.headOption)
      ddlDrop <- dal.database.run(ddl.drop)
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
      ddlCreate <- dal.database.run(ddl.create)
      ins <- dal.database.run(DBIO.seq(
        dal.companies += company1,
        dal.companies += company2,
        dal.friendships += friendship
      ))
      areFriends <- dal.database.run(dal.companiesAreFriends(company1.id, company2.id).head)
      ddlDrop <- dal.database.run(ddl.drop)
    } yield areFriends
    Await.ready(f, Duration(1, "second"))
    f.value match {
      case Some(Success(true)) => // pass
      case Some(Failure(e)) => fail(e)
      case None => fail()
    }
  }
}
