name := "service-backend"

version := "1.0"

scalaVersion := "2.11.5"

resolvers += "spray repo" at "http://repo.spray.io"

libraryDependencies += "io.spray" %% "spray-can" % "1.3.3"

libraryDependencies += "io.spray" %% "spray-httpx" % "1.3.3"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.11"