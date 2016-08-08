name := "GoogleFileOrganizer"

version := "1.0"

scalaVersion := "2.11.8"

resolvers += Resolver.sonatypeRepo("snapshots")
resolvers += "ERI OSS" at "http://dl.bintray.com/elderresearch/OSS"
libraryDependencies ++= Seq(
  "edu.eckerd" %% "google-api-scala" % "0.1.1-SNAPSHOT",
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "org.xerial" % "sqlite-jdbc" % "3.8.11.2",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.1.1",
  "com.elderresearch" %% "ssc" % "0.2.0",
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "org.postgresql" % "postgresql" % "9.4.1209",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0"
)