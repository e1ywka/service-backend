/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import akka.actor.Scheduler
import slick.driver.H2Driver
import slick.driver.H2Driver.api._

object H2Dal {
  implicit val scheduler: Scheduler = implicitly[Scheduler]
  def apply(dbName: String): H2Dal = new H2Dal(Database.forURL(
    url = s"jdbc:h2:mem:$dbName;DATABASE_TO_UPPER=false",
    driver = "org.h2.Driver",
    keepAliveConnection = true)
  )
}

class H2Dal private (val database: Database)(implicit val scheduler: Scheduler) extends Dal {
  val driver = H2Driver
}
