val scala3Version = "3.4.2"

lazy val root = project
.in(file("."))
.settings(
    name := "podcaster",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    semanticdbEnabled := true, // enable SemanticDB
    scalacOptions += "-Wunused:imports",

    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "com.softwaremill.sttp.client4" %% "core" % "4.0.0-M17",
      "org.scala-lang.modules" %% "scala-xml" % "2.2.0",
      "org.tomlj" % "tomlj" % "1.1.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-classic" % "1.3.5",
      "org.rogach" %% "scallop" % "5.1.0" 
    )
)

inThisBuild(
  List(
    scalaVersion := scala3Version,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)
