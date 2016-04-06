// --------------------------------------------------
// build.sbt | Scala build file for the project
//    author | Luca Gilardi <lucag@icsi.berkeley.edu>
// --------------------------------------------------
// Docker

//enablePlugins(DockerPlugin)

//import sbtdocker.DockerKeys._
packAutoSettings

name := "TwitterSentimentAnalyzer"

version := "0.2.2"

scalaVersion := "2.10.6"

unmanagedClasspath in Compile += baseDirectory.value / "etc"
//unmanagedClasspath in Runtime += baseDirectory.value / "data"
//  , baseDirectory.value / "resources"

includeFilter in (Runtime, unmanagedResources) := "*.conf"

unmanagedResourceDirectories in Compile += baseDirectory.value / "etc"
//unmanagedResourceDirectories in Runtime += baseDirectory.value / "data"

//dockerfile in docker := {
//  // The assembly task generates a fat JAR file
//  val artifact: File = assembly.value
//  val artifactTargetPath = s"/app/${artifact.name}"
//
//  new Dockerfile {
//    from("jupyter/all-spark-notebook")
//    add(artifact, artifactTargetPath)
//    add(s"${baseDirectory.value}/etc", "/app/etc")
//    add(s"${baseDirectory.value}/data", "/app/data")
//    add(s"${baseDirectory.value}/var", "/app/var")
//    add(s"${baseDirectory.value}/notebooks", "/app/notebooks")
//    entryPoint("bash", "-c", "/app/bin/run")
//  }
//}

// The exclusions are needed when building the assembly überjar
libraryDependencies ++= Seq(
  ("org.apache.spark"                    %% "spark-core"                     % "1.6.1" % "provided")
    .exclude("org.mortbay.jetty",           "servlet-api")
    .exclude("commons-beanutils",           "commons-beanutils-core")
    .exclude("commons-collections",         "commons-collections")
    .exclude("org.apache.spark",            "spark-unsafe")
    .exclude("org.apache.spark",            "spark-network-common")
    .exclude("org.apache.spark",            "spark-network-shuffle")
    .exclude("commons-logging",             "commons-logging")
    .exclude("com.esotericsoftware.minlog", "minlog"),
  "org.apache.spark"                    %%  "spark-streaming"                % "1.6.1" % "provided",
  "com.jsuereth"                        %%  "scala-arm"                      % "1.4",
  "org.json4s"                          %%  "json4s-jackson"                 % "3.3.0",
  "io.spray"                            %%  "spray-json"                     % "1.3.2",
  ("org.apache.spark"                   %%  "spark-streaming-twitter"        % "1.6.1")
    .exclude("commons-beanutils",           "commons-beanutils-core")
    .exclude("commons-collections",         "commons-collections")
    .exclude("commons-logging",             "commons-logging")
    .exclude("com.esotericsoftware.minlog", "minlog")
    .exclude("org.spark-project.spark",     "unused")
    .excludeAll(ExclusionRule(name = "spark-*")),
  ("org.cloudera.spark.streaming.kafka"  % "spark-kafka-writer"              % "0.1.1-SNAPSHOT")
      .excludeAll(ExclusionRule(organization = "org.apache.spark"))
  ,
  "edu.stanford.nlp"                    % "stanford-corenlp"                % "3.6.0",
  "edu.stanford.nlp"                    % "stanford-corenlp"                % "3.6.0" classifier "models"
//  "edu.cmu.cs"                          % "ark-tweet-nlp"                   % "0.3.3"
)

resolvers ++= Seq(
  "Local Maven"                   at Path.userHome.asFile.toURI.toURL + ".m2/repository",
  "JBoss Repository"              at "http://repository.jboss.org/nexus/content/repositories/releases/",
  "Spray Repository"              at "http://repo.spray.cc/",
  "Cloudera Repository"           at "https://repository.cloudera.com/artifactory/cloudera-repos/",
  "Cloudera Snapshots"            at "https://repository.cloudera.com/artifactory/libs-snapshot-local",
  "Akka Repository"               at "http://repo.akka.io/releases/",
  "Twitter4J Repository"          at "http://twitter4j.org/maven2/",
  "Apache HBase"                  at "https://repository.apache.org/content/repositories/releases",
  "Twitter Maven Repo"            at "http://maven.twttr.com/",
//  "scala-tools"                   at "https://oss.sonatype.org/content/groups/scala-tools",
  "Typesafe repository"           at "http://repo.typesafe.com/typesafe/releases/",
  "Second Typesafe repo"          at "http://repo.typesafe.com/typesafe/maven-releases/",
  "Mesosphere Public Repository"  at "http://downloads.mesosphere.io/maven",
  Resolver.sonatypeRepo("public"),
  Resolver.mavenLocal
)