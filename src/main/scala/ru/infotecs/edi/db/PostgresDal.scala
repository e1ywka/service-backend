/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import akka.actor.Scheduler
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.driver.PostgresDriver
import slick.driver.PostgresDriver.api._

object PostgresDal {
  implicit val scheduler: Scheduler = implicitly[Scheduler]

  def apply(): PostgresDal = {
    val cpds = new ComboPooledDataSource()
    new PostgresDal(Database.forDataSource(cpds))
  }
}

class PostgresDal(val database: Database)(implicit val scheduler: Scheduler) extends Dal {
  val driver = PostgresDriver
}
