/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import slick.driver.JdbcProfile

trait DriverComponent {
  val driver: JdbcProfile
}
