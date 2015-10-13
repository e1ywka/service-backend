name := "ru.infotecs.edi.service-backend"

version := "1.0"

scalaVersion := "2.11.7"

val akkaVersion = "2.4.0"

assemblyJarName in assembly := {
  val versionStr = version.value
  s"service-backend-${versionStr}.jar"
}

mainClass in assembly := Some("ru.infotecs.edi.service.Main")

fork in run := true

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies += "io.spray" %% "spray-can" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-routing" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-json" % "1.3.2"

libraryDependencies += "io.spray" %% "spray-testkit" % "1.3.3" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % akkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % akkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % akkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % akkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion

libraryDependencies += "io.kamon" % "sigar-loader" % "1.6.6-rev002"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

libraryDependencies += "org.postgresql" % "postgresql" % "9.4-1201-jdbc41"

libraryDependencies += "com.mchange" % "c3p0" % "0.9.5"

libraryDependencies += "com.h2database" % "h2" % "1.4.188" % "test"

libraryDependencies += "com.typesafe.slick" %% "slick" % "3.0.1"

libraryDependencies += "net.iharder" % "base64" % "2.3.8"