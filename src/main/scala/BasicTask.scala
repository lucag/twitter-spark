/**
  * Created by lucag on 2/2/16.
  */

import java.util
import java.util.Properties

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations
import edu.stanford.nlp.util.{CoreMap, ArrayCoreMap}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.twitter._
import org.json4s.NoTypeHints
import twitter4j.Status

import scala.io.Source
import scala.language.postfixOps

/** Data access and OAuth settings */
object Data {
  // Base directory for all data files
  def baseDir: String = sys.props("base.data.dir")

  // File from a resource directory (see build.sbt)
  def resource(name: String) = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name))

  // read file from data directory
  def file(name: String) = Source.fromFile(baseDir + name)

  // Side-effecting (but idempotent) configuration of the Oauth Credentials for accessing Twitter
  def updateEnvironment(): Unit = {
    val lines = resource("twitter.conf") // from resources (see build.sbt)
      .getLines
      .filter(_.trim.nonEmpty)
      .toSeq

    // Pattern matching and Either objects to avoid possibly malformed lines
    val pairs = lines map {
      line => line.split("\\s*=\\s*") match {
        case Array(k, v) => Right((k, v))
        case _ => Left(s"In $line: must be <path.to.key> = <value>")
      }
    }

    println("Pushing the following onto environment:")
    pairs.foreach {
      case Right((key, value)) =>
        System.setProperty(key, value)
        println(s"  $key = $value")
      case Left(message) => println(s"Error: $message")
    }
  }

  // The handles of the airlines we're interested in
  def handles: Seq[String] = file("/data/airline-twitter-sentiment/airline-handles")
    .getLines
    .map {
      _.split(",") match {
        case Array(_, v) => Some(v.toLowerCase)
        case _ => None
      }
    }
    .collect { case Some(s) => s }
    .toSeq
}

object BasicTask {
  type *** = Any
  type ??? = Nothing

  // This implements the required funtion: find top counts (handles in this case) in a window
  def printTopSymbols(dstream: DStream[Status], k: Int, window: Duration): Unit = {
    dstream
      .flatMap(_.getUserMentionEntities) // extract and concatenate "sysmbol entities"
      .map(s => (s.getText, 1)) // make them into pairs, as expected by reduceByKeyAndWindow
      .reduceByKeyAndWindow(_ + _, _ - _, window) // sum over this window, subtracting the previos
      .map { case (screenName, count) => (count, screenName) } // flip
      .transform(_.sortByKey(ascending = false)) // sort by first element in tuple, ascending
      .foreachRDD { rdd =>
      println(s"Top $k handles in the last $window (non-cumulative):\n${rdd.take(k).mkString(", ")}")
    }
  }

  // This is just a general test
  def run(): Unit = {
    Data.updateEnvironment()

    val log4j = s"-Dlog4j.configuration=${Data.baseDir}/etc/log4j.properties"

    println("Set Log to Warn")
    Logger.getRootLogger.setLevel(Level.WARN)

    val conf = new SparkConf()
      .setAppName("TwitterSentimenter")
      .setMaster("local[*]")
      .setExecutorEnv("spark.driver.extraJavaOptions", log4j)
      .setExecutorEnv("spark.executor.extraJavaOptions", log4j)

    val ssc = new StreamingContext(conf, Minutes(1))

    // Set chackpoint directory
    ssc.checkpoint(Data.baseDir + "/var/checkpoint")

    // The handles we want to extract
    //    val relevantHandles = Set(Data.handles: _*)

    //    val twitter = new TwitterFactory().getInstance()
    val tweets = TwitterUtils.createStream(ssc, None, Data.handles.toArray)


    //    val handleRE = """@\w+""".r
    // Select tweets by "relevant" handles
    //    tweets.filter { tweet =>
    //        handleRE
    //          .findAllIn(tweet.getText)
    //          .exists(relevantHandles.contains)
    //    }

    def toTSV(tweet: Status): String = {
      val user = tweet.getUser
      val place = tweet.getPlace
      val createdAt = tweet.getCreatedAt

      Seq(tweet.getId,
        tweet.getLang,
        if (user != null) user.getName else null,
        if (place != null) place.getFullName else null,
        tweet.getGeoLocation,
        if (createdAt != null) createdAt.getTime else null,
        tweet.getCreatedAt,
        tweet.getRetweetCount,
        tweet.getText,
        tweet.isTruncated
      ).mkString("\t")
    }

    // Turn all tweets to JSON
    tweets.map(toTSV).saveAsTextFiles(s"${Data.baseDir}/var/relevant-tweets")

    printTopSymbols(tweets, 10, Minutes(1))

    ssc.start()

    sys.addShutdownHook(ssc.stop())

    Thread.sleep(Minutes(6 * 60) milliseconds)

    ssc.stop()
  }

  def main(args: Array[String]) {
    run()
  }
}

object Pipeline {
  def make(key: String, value: String) = {
    val props = new Properties
    props.setProperty(key, value)
    new StanfordCoreNLP(props)
  }
}

object Sentiment {
  import scala.collection.convert.Wrappers._

  lazy val pipeline = Pipeline.make("annotators", "tokenize, ssplit, parse, lemma, ner, sentiment")

  def analyze(sentence: String): Double = if (sentence.length > 0) {
    val annotation = pipeline.process(sentence)
    val sentiments = new JListWrapper(annotation.get(classOf[CoreAnnotations.SentencesAnnotation]))
      .map { s =>
        val tree = s.get(classOf[SentimentCoreAnnotations.SentimentAnnotatedTree])
        RNNCoreAnnotations.getPredictedClass(tree)
      }
    sentiments.sum.toDouble / sentiments.length
  }
  else 0.0
}

object ErrorTest {
  import Sentiment._
  import org.json4s.native.Serialization.{read, write => swrite}

  implicit val formats = org.json4s.native.Serialization.formats(NoTypeHints)

  case class PolarizedText(text: String, polarity: String)

  def process(fileName: String): Unit = {
    val conf = new SparkConf()
      .setAppName("ErrorTest")
      .setMaster("local[*]")

    def polarity(sentimentValue: Double) =
      if (sentimentValue <= 2) "-"
      else if (2 > sentimentValue && sentimentValue < 3) "="
      else "+"

    val Array(base, ext) = fileName.split("\\.")

    val sc = new SparkContext(conf)
      sc.textFile(fileName, 4)
        .map { line =>
          val text: String = read[PolarizedText](line).text
          swrite(new PolarizedText(text, polarity(analyze(text))))
        }
      .saveAsTextFile(s"$base-analyzed.$ext")
  }
}