/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

trait PersonModel {
  this: DriverComponent with DbModel =>

  import driver.api._

  def findPersonById(id: UUID) = persons.filter(p => p.id === id)
}
