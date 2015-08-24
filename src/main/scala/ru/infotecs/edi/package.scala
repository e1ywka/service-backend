package ru.infotecs

import java.util.UUID

/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package object edi {

  object UUIDUtils {
    def fromString(s: String): UUID = {
      try {
        return UUID.fromString(s)
      }
      catch {
        case e: IllegalArgumentException => {
          val uuid: String = s.replaceAll(".*(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")
          return UUID.fromString(uuid)
        }
      }
    }

    def noDashString(uuid: UUID): String = {
      return uuid.toString.replaceAll("-", "")
    }
  }

}
