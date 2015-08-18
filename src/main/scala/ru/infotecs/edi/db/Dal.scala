/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import akka.actor.Scheduler
import akka.pattern.CircuitBreaker
import scala.concurrent.Future
import scala.concurrent.duration._

abstract class Dal extends DriverComponent with DbModel with CompanyModel {
  import driver.api._

  val database: Database
  val scheduler: Scheduler

  val circuitBreaker = CircuitBreaker(scheduler, 2, 10 seconds, 5 seconds)

  def withCircuitBreaker[T](f: => Future[T]) = circuitBreaker.withCircuitBreaker(f)
}
