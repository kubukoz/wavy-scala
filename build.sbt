name := "wavy-scala"

scalaVersion := "2.12.10"

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
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
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
  requireJsDomEnv in Test := true
) ++ addCommandAlias("dev", ";client/fastOptJS::startWebpackDevServer;~client/fastOptJS") ++ addCommandAlias(
  "build",
  "client/fullOptJS::webpack"
)

val client = project.settings(slinkySettings).enablePlugins(ScalaJSBundlerPlugin)

val root = project.in(file(".")).dependsOn(client).aggregate(client)
