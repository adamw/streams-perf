import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.perf",
  scalaVersion := "3.3.1"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.17" % Test

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, name := "streams-perf")
  .aggregate(core, core2)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.ox" %% "core" % "0.0.18",
      "com.typesafe.akka" %% "akka-stream" % "2.6.20",
      "io.monix" %% "monix" % "3.4.1",
      "io.monix" %% "monix-catnap" % "3.4.1",
      scalaTest
    )
  )

lazy val core2: Project = (project in file("core2"))
  .settings(commonSettings: _*)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "3.9.2",
      scalaTest
    )
  )
