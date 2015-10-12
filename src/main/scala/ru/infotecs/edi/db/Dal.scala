/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

abstract class Dal extends DriverComponent with DbModel with CompanyModel with PersonModel {
  import driver.api._

  val database: Database
}
