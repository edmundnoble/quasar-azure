package quasar.azure.project

import scala.Boolean
import scala.collection.Seq

import sbt._

object Dependencies {
  // quasar version with LWC support
  private val quasarVersion       = "42.1.1"

  private val scalaXmlVersion     = "1.1.0"

  private val circeJawnVersion    = "0.8.0"

  private val http4sVersion       = "0.16.6a"

  private val specsVersion        = "4.0.2"

  // the connector's dependencies, excluding quasar.
  def lwcCore = Seq(
    "org.http4s"                 %% "http4s-scala-xml"          % http4sVersion,
    "org.http4s"                 %% "http4s-blaze-client"       % http4sVersion,
    "org.scala-lang.modules"     %% "scala-xml"                 % scalaXmlVersion,
    "io.circe"                   %% "circe-jawn"                % circeJawnVersion
  )

  // we need to separate quasar out from the LWC dependencies,
  // to keep from packaging it and its dependencies.
  def lwc = lwcCore ++ Seq(
    "org.quasar-analytics"       %% "quasar-mimir-internal"     % quasarVersion
  )

  // extra dependencies for integration tests, for now.
  def it = lwc ++ Seq(
    "org.specs2" %% "specs2-core"               % specsVersion                         % Test,
    "org.specs2" %% "specs2-scalacheck"         % specsVersion                         % Test,
    "org.specs2" %% "specs2-scalaz"             % specsVersion                         % Test
  )
}
