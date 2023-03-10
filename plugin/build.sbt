sbtPlugin := true

name := "sbtix"
organization := "se.nullable.sbtix"
version := "0.2-SNAPSHOT"

// publishTo := Some(
//   if (isSnapshot.value) {
//     Opts.resolver.sonatypeSnapshots
//   } else {
//     Opts.resolver.sonatypeStaging
//   }
// )

licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
homepage := Some(url("https://gitlab.com/teozkr/Sbtix"))

scmInfo := Some(
  ScmInfo(
    url("https://gitlab.com/teozkr/sbtix"),
    "scm:git@gitlab.com:teozkr/sbtix.git"
  )
)

developers := List(
  Developer(
    id = "teozkr",
    name = "Teo Klestrup Röijezon",
    email = "teo@nullable.se",
    url = url("https://nullable.se")
  )
)

// TODO: Restore if necessary
// useGpg := false

// pgpPublicRing := Path.userHome / ".gnupg" / "pubring.kbx"
// Secret rings are no more, as of GPG 2.2
// See https://github.com/sbt/sbt-pgp/issues/126
// pgpSecretRing := pgpPublicRing.value

// addSbtPlugin("io.get-coursier" % "sbt-coursier" % "2.0.14")

enablePlugins(SbtPlugin)

scriptedLaunchOpts ++= Seq(
  s"-Dplugin.version=${version.value}"
)
scriptedBufferLog := false

publishMavenStyle := false

Compile / packageBin / publishArtifact := true

Test / packageBin / publishArtifact := false

Compile / packageDoc / publishArtifact := false

Compile / packageSrc / publishArtifact := false

Compile / unmanagedResourceDirectories += baseDirectory.value / "nix-exprs"

scalafmtOnCompile := true

// TODO: See how to re-use same principles
libraryDependencies += "com.slamdata" %% "matryoshka-core" % "0.21.3"
libraryDependencies += "org.scalaz" %% "scalaz-concurrent" % "7.2.35"

// Coursier low-level api
libraryDependencies += "io.get-coursier" %% "coursier-core" % "2.0.13"
libraryDependencies += "io.get-coursier" %% "coursier-cache" % "2.0.13"

// Coursier api
// libraryDependencies += "io.get-coursier" %% "coursier" % "2.0.13"
