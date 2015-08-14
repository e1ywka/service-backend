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

  def apply(jdbcUrl: String): PostgresDal = {
    val cpds = new ComboPooledDataSource()
    cpds.setDriverClass( "org.postgresql.Driver" )
    cpds.setJdbcUrl(jdbcUrl)

    cpds.setMinPoolSize(5)
    cpds.setAcquireIncrement(5)
    cpds.setMaxPoolSize(20)
    new PostgresDal(Database.forDataSource(cpds))
  }
}

class PostgresDal(val database: Database)(implicit val scheduler: Scheduler) extends Dal {
  val driver = PostgresDriver
}
