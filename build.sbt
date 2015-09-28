name := "ru.infotecs.edi.service-backend"

version := "1.0"

scalaVersion := "2.11.6"

assemblyJarName in assembly := {
  val versionStr = version.value
  s"service-backend-${versionStr}.jar"
}

mainClass in assembly := Some("ru.infotecs.edi.service.Main")

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies += "io.spray" %% "spray-can" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-routing" % "1.3.3"

libraryDependencies += "io.spray" %%  "spray-json" % "1.3.2"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.11"

libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.3.11"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.3.11"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

libraryDependencies += "org.postgresql" % "postgresql" % "9.4-1201-jdbc41"

libraryDependencies += "com.mchange" % "c3p0" % "0.9.5"

libraryDependencies += "com.h2database" % "h2" % "1.4.188" % "test"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.0.1"

libraryDependencies += "net.iharder" % "base64" % "2.3.8"