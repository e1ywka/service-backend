/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import akka.actor.Scheduler
import slick.driver.H2Driver
import slick.driver.H2Driver.api._

object H2Dal {
  implicit val scheduler: Scheduler = implicitly[Scheduler]
  def apply(config: String): H2Dal = new H2Dal(Database.forConfig(config))
}

class H2Dal(val database: Database)(implicit val scheduler: Scheduler) extends Dal {
  val driver = H2Driver
}
