ThisBuild / tlBaseVersion := "0.1" // 0.1.0 is already on Central (published from epimetheus-community)

ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / tlCiReleaseBranches := Seq("main")


val Scala3 = "3.3.8"

ThisBuild / crossScalaVersions := Seq("2.13.18", Scala3)
ThisBuild / scalaVersion := Scala3

ThisBuild / testFrameworks += new TestFramework("munit.Framework")


val munitCatsEffectV = "2.0.0-M3"


// Projects
lazy val `epimetheus-rediculous` = tlCrossRootProject
  .aggregate(core)

lazy val core = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "epimetheus-rediculous",

    libraryDependencies ++= Seq(
      "io.chrisdavenport" %%% "epimetheus" % "0.5.0",
      "io.chrisdavenport" %%% "rediculous" % "0.5.0",

      "org.typelevel"               %%% "munit-cats-effect"        % munitCatsEffectV         % Test,

    )
  )

lazy val site = project.in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    laikaTheme := tlSiteHelium.value.site
      .topNavigationBar(
        homeLink = laika.helium.config.IconLink.internal(laika.ast.Path.Root / "index.md", laika.helium.config.HeliumIcon.home)
      )
      .build
  )
  .dependsOn(core.jvm)
