/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi.db

import java.util.UUID

trait CompanyModel {
  this: DriverComponent with DbModel =>

  import driver.api._

  /**
   * Find company by id.
   * @param id Company id
   * @return company.
   */
  def find(id: UUID) = {
    companies.filter(c => c.id === id)
  }

  /**
   * Find company by INN and KPP.
   * @param inn company's INN.
   * @param kpp company's KPP.
   * @return company.
   */
  def find(inn: String, kpp: Option[String]) = {
    kpp match {
      case Some(k) => companies.filter(c => (c.inn === inn) && (c.kpp === k))
      case None => companies.filter(c => c.inn === inn)
    }
  }

  /**
   * Check companies are ''friends''. They have record in table ''b2b_friendship'' with status ACCEPTED.
   * @param company1 first company.
   * @param company2 second company.
   * @return
   */
  def companiesAreFriends(company1: UUID, company2: UUID) = {
    require(company1 != null)
    require(company2 != null)
    val c1 = company1.toString
    val c2 = company2.toString

    sql"""SELECT EXISTS (SELECT 1 FROM b2b_friendship
          WHERE initiator_contractor_uuid = $c1 and recipient_contractor_uuid = $c2 and status = 'ACCEPTED'
          UNION ALL
          SELECT 1 FROM b2b_friendship
          WHERE initiator_contractor_uuid = $c2 and recipient_contractor_uuid = $c1 and status = 'ACCEPTED')"""
      .as[Boolean]
  }
}
