// --------------------------------------------------
// build.sbt | Scala build file for the project
//    author | Luca Gilardi <lucag@icsi.berkeley.edu>
// --------------------------------------------------
// Docker

enablePlugins(DockerPlugin)

//import sbtdocker.DockerKeys._
packAutoSettings

name := "TwitterSentimentAnalyzer"

version := "0.2.0"

scalaVersion := "2.10.5"

unmanagedClasspath in Compile += baseDirectory.value / "etc"
//unmanagedClasspath in Runtime += baseDirectory.value / "data"
//  , baseDirectory.value / "resources"

includeFilter in (Runtime, unmanagedResources) := "*.conf"

unmanagedResourceDirectories in Compile += baseDirectory.value / "etc"
//unmanagedResourceDirectories in Runtime += baseDirectory.value / "data"

dockerfile in docker := {
  // The assembly task generates a fat JAR file
  val artifact: File = assembly.value
  val artifactTargetPath = s"/app/${artifact.name}"

  new Dockerfile {
    from("jupyter/all-spark-notebook")
    add(artifact, artifactTargetPath)
    add(s"${baseDirectory.value}/etc", "/app/etc")
    add(s"${baseDirectory.value}/data", "/app/data")
    add(s"${baseDirectory.value}/var", "/app/var")
    add(s"${baseDirectory.value}/notebooks", "/app/notebooks")
    entryPoint("bash", "-c", "/app/bin/run")
  }
}

libraryDependencies ++= Seq(
  "org.apache.spark"  %% "spark-core"                     % "1.6.0" % "provided",
  "org.apache.spark"  %% "spark-streaming"                % "1.6.0" % "provided",
  "edu.stanford.nlp"   % "stanford-corenlp"               % "3.6.0" % "provided",
  "org.json4s"        %% "json4s-native"                  % "3.3.0" % "provided",
  "edu.stanford.nlp"   % "stanford-corenlp"               % "3.6.0" classifier "models",
  ("org.apache.spark"  % "spark-streaming-twitter_2.10"   % "1.6.0")
    .exclude("org.spark-project.spark", "unused") // this works
)

resolvers ++= Seq(
  "JBoss Repository"              at "http://repository.jboss.org/nexus/content/repositories/releases/",
  "Spray Repository"              at "http://repo.spray.cc/",
  "Cloudera Repository"           at "https://repository.cloudera.com/artifactory/cloudera-repos/",
  "Akka Repository"               at "http://repo.akka.io/releases/",
  "Twitter4J Repository"          at "http://twitter4j.org/maven2/",
  "Apache HBase"                  at "https://repository.apache.org/content/repositories/releases",
  "Twitter Maven Repo"            at "http://maven.twttr.com/",
//  "scala-tools"                   at "https://oss.sonatype.org/content/groups/scala-tools",
  "Typesafe repository"           at "http://repo.typesafe.com/typesafe/releases/",
  "Second Typesafe repo"          at "http://repo.typesafe.com/typesafe/maven-releases/",
  "Mesosphere Public Repository"  at "http://downloads.mesosphere.io/maven",
  Resolver.sonatypeRepo("public")
)