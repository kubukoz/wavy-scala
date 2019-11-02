val CatsEffectVersion = "2.0.0"
val Fs2Version = "2.0.1"
val Http4sVersion = "0.21.0-M5"
val CirceVersion = "0.12.1"
val DoobieVersion = "0.8.4"
val FlywayVersion = "5.0.5"
val LogbackVersion = "1.2.3"
val ScalaTestVersion = "3.0.8"
val ScalaCheckVersion = "1.13.4"

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x cross CrossVersion.full)

val compilerPlugins = List(
  crossPlugin("org.scalamacros" % "paradise" % "2.1.1"),
  crossPlugin("org.typelevel" % "kind-projector" % "0.11.0"),
  crossPlugin("com.github.cb372" % "scala-typed-holes" % "0.1.1"),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
)

val commonSettings = Seq(
  name := "wavy-scala",
  organization := "io",
  scalaVersion := "2.12.10",
  scalacOptions -= "-Xfatal-warnings",
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-effect" % CatsEffectVersion,
    "co.fs2" %%% "fs2-core" % Fs2Version,
    "io.circe" %%% "circe-core" % CirceVersion,
    "io.circe" %%% "circe-generic" % CirceVersion,
    "org.typelevel" %%% "simulacrum" % "1.0.0"
  ) ++ compilerPlugins
)

val npmDeps = Seq(
  "react" -> "16.11.0",
  "react-dom" -> "16.11.0",
  "react-proxy" -> "1.1.8",
  "file-loader" -> "3.0.1",
  "style-loader" -> "0.23.1",
  "css-loader" -> "2.1.1",
  "html-webpack-plugin" -> "3.2.0",
  "copy-webpack-plugin" -> "5.0.2",
  "webpack-merge" -> "4.2.1"
)

val slinkySettings = Seq(
  npmDependencies in Compile ++= npmDeps,
  libraryDependencies ++= Seq(
    "me.shadaj" %%% "slinky-web" % "0.6.3",
    "me.shadaj" %%% "slinky-hot" % "0.6.3",
    "org.scalatest" %%% "scalatest" % "3.0.5" % Test
  ),
  webpack / version := "4.29.6",
  startWebpackDevServer / version := "3.2.1",
  webpackResources := baseDirectory.value / "webpack" * "*",
  fastOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-fastopt.config.js"),
  fullOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-opt.config.js"),
  webpackConfigFile in Test := Some(baseDirectory.value / "webpack" / "webpack-core.config.js"),
  webpackDevServerExtraArgs in fastOptJS := Seq("--inline", "--hot"),
  webpackBundlingMode in fastOptJS := BundlingMode.LibraryOnly(),
  requireJsDomEnv in Test := true,
  scalacOptions += "-P:scalajs:sjsDefinedByDefault"
) ++ addCommandAlias("dev", ";client/fastOptJS::startWebpackDevServer;~client/fastOptJS") ++ addCommandAlias(
  "build",
  "client/fullOptJS::webpack"
)

val client = project.settings(commonSettings).settings(slinkySettings).enablePlugins(ScalaJSBundlerPlugin)

val app = project
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.pureconfig" %% "pureconfig-generic" % "0.12.1",
      "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "org.flywaydb" % "flyway-core" % FlywayVersion,
      "org.tpolecat" %% "doobie-core" % DoobieVersion,
      "org.tpolecat" %% "doobie-postgres" % DoobieVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "io.scalaland" %% "chimney" % "0.3.2"
    ) ++ compilerPlugins
  )

val root = project.in(file(".")).dependsOn(client).aggregate(client)
