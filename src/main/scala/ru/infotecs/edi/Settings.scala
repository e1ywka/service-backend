/*
 * Copyright 2015 Infotecs. All rights reserved.
 */
package ru.infotecs.edi

import akka.actor._
import com.typesafe.config.Config

class SettingsImpl(config: Config) extends Extension {
  val BindHost = config.getString("infotecs.service.bind.host")
  val BindPort: Int = config.getInt("infotecs.service.bind.port")
  val OperatorFnsId = config.getString("infotecs.operator.fnsId")
  val OperatorCompanyName = config.getString("infotecs.operator.company")
  val OperatorCompanyInn = config.getString("infotecs.operator.inn")
  val FileServerHost = config.getString("infotecs.file-server.host")
  val FileServerPort = config.getInt("infotecs.file-server.port")
  val FileServerUploadUrl = config.getString("infotecs.file-server.uploadUrl")
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): SettingsImpl =
    new SettingsImpl(system.settings.config)

  override def lookup() = Settings
}
