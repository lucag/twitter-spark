/**
  * Created by lucag on 2/2/16.
  */

import java.io._
import java.util.Properties

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.twitter._
import org.apache.spark.{Logging, SparkConf, SparkContext}
import twitter4j.{Status, UserMentionEntity}

import scala.io.Source
import scala.language.postfixOps

object Config extends Logging {
  import Data._

  // Side-effecting (but idempotent) configuration of the OAuth Credentials for accessing Twitter
  def updateEnvironment(): Unit = {
    val lines = resource("twitter.conf") // from resources (see build.sbt)
      .getLines
      .filter(_.trim.nonEmpty)
      .toSeq

    // Pattern matching and Either objects to avoid possibly malformed lines
    val pairs = lines map { line =>
      line.split("\\s*=\\s*") match {
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

  // Set reasonable logging levels
  def setStreamingLogLevels() {
    val log4jInitialized = Logger.getRootLogger.getAllAppenders.hasMoreElements
    if (!log4jInitialized) {
      // We first log something to initialize Spark's default logging, then we override the
      // logging level.
      logInfo("Setting log level to [WARN] for streaming example."
              + "To override add a custom log4j.properties to the classpath")
      Logger.getRootLogger.setLevel(Level.WARN)
    }
  }
}

/** Data access and OAuth settings */
object Data {
  // Base directory for all data files
  def baseDir: String = sys.env("APP_BASE")

  // File from a resource directory (see build.sbt)
  def resource(name: String) = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name))

  // read file from data directory
  def file(name: String) = Source.fromFile(baseDir + name)

  def fileAppender(name: String) = new PrintStream(new FileOutputStream(new File(name), true))

  // The handles of the airlines we're interested in
  def handles: Seq[String] = file("/data/airline-twitter-sentiment/airline-handles")
    .getLines
    .map {
      _.split(",") match {
        case Array(_, v) => Some(v.toLowerCase)
        case _           => None
      }
    }
    .collect { case Some(s) => s }
    .toSeq
}

object BasicTask {
  type ??? = Nothing

  // This implements the required function: find top counts (handles in this case) in a window
  // NOTE: | here and below this design is not optimal: in a real-world implementation,
  //       | the PrintWriter would be shared (with the constraint that it cannot be serialized
  //       | between driver and executors)
  def reportTopSymbols(dstream: DStream[UserMentionEntity], k: Int, window: Duration, fileName: String): Unit = {
    dstream
      .map(e => (e.getText, 1))                           // make them into pairs, as expected by reduceByKeyAndWindow
      .reduceByKeyAndWindow(_ + _, _ - _, window)         // sum over this window, subtracting the previos
      .map { case (mention, count) => (count, mention) }  // flip
      .transform(_.sortByKey(ascending = false))          // sort by first element in tuple, ascending
      .foreachRDD { rdd =>
        Data.fileAppender(fileName)
          .append("_" * 72 + "\n")
          .append {
            val lines = rdd.take(k)
              .filter(_._1 > 0)
              .map { case (count, mention) => s"$count -> @$mention" }
              .mkString("\n  ")
            s"Top $k handles in the last $window (non-cumulative):\n  $lines\n"
          }
          .close()
    }
  }

  // Report airlines, grouping by polarity
  // NOTE: this design is not optimal.
  def reportSentimentByAirline(tweets: DStream[Status], window: Duration, fileName: String): Unit = {
    tweets.map { pair =>
        val text = pair.getText
        val sent = Sentiment.analyze(text)
        val pol = Sentiment.polarity(sent)
        (pol, f"[$sent%.1f]: $text%s")
      }
      .groupByKeyAndWindow(window) // group by polarity
      .foreachRDD { rdd =>
        Data.fileAppender(fileName)
          .append("_" * 72 + "\n")
          .append(s"Tweets with sentiment in the last $window:\n")
          .close()

      rdd.foreach {
        // each element is a (polarity, Iterable[String]) pair
        case (polarity, lines) => Data.fileAppender(fileName)
          .append {
            s"""  ($polarity)
                |    ${lines.mkString("\n    ")}
                |""" stripMargin
          }
          .close()
      }
    }
  }

  // This is just a general test
  def run(): Unit = {
    Config.updateEnvironment()
    Config.setStreamingLogLevels()

    val log4j = s"-Dlog4j.configuration=${Data.baseDir}/etc/log4j.properties"

    println("Set Log to Warn")
    Logger.getRootLogger.setLevel(Level.WARN)

    // Create Spark context
    val conf = new SparkConf()
      .setAppName("TwitterSentimenter")
      .setMaster("local[*]")
      .setExecutorEnv("spark.driver.extraJavaOptions", log4j)
      .setExecutorEnv("spark.executor.extraJavaOptions", log4j)

    val ssc = new StreamingContext(conf, Minutes(1))

    // Set checkpoint directory: needed for the
    ssc.checkpoint(Data.baseDir + "/var/checkpoint")

    // The handles we want to extract (without the initial @)
    val airlineHandles = Set(Data.handles.map(_.substring(1)) :_*)

    val tweets = TwitterUtils.createStream(ssc, None, Data.handles.toArray)
      .filter { s =>
        s.getLang match {
          case "en" => ! s.isRetweet   // English-only tweets, no retweets
          case _    => false
        }
      }

    // Serialize a few fields of a Status (Tweet) into a tab-separated record
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

    // Turn all tweets to JSON and save them
    //    tweets.map(toTSV).saveAsTextFiles(s"${Data.baseDir}/var/relevant-tweets")

    val topItemCount  = 10                // How many items to report
    val windowTime    = Minutes(5)        // Window timeframe duration

    // Extract relevant user mentions
    val userMentions = tweets
      .flatMap(_.getUserMentionEntities)  // extract and concatenate user mention
      .filter(e => airlineHandles.contains(e.getText.toLowerCase()))

    val fileName = s"${Data.baseDir}/var/report.txt"
    println(s"Reporting to $fileName")

    reportTopSymbols(userMentions, topItemCount, windowTime, fileName)

    // Tweets with sentiment in the current window
    reportSentimentByAirline(tweets, windowTime, fileName)

    ssc.start()

    // Terminate gracefully on signals
    sys.addShutdownHook {
      println("Signal received, shutting down")
      ssc.stop()
    }

    Thread.sleep(Minutes(5 * 60) milliseconds)

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

  def polarity(sentimentValue: Double) =
    if (sentimentValue < 2.0)
      "-"
    else if (2.0 <= sentimentValue && sentimentValue < 2.05)
      "="
    else
      "+"

  val badOnes = "\uFE0F|\uD83D|\uD83C".r

  def analyze(sentence: String): Double = if (sentence.length > 0) {
    val cleanedSentence = badOnes.replaceAllIn(sentence, " ")
    val annotation = pipeline.process(cleanedSentence)
    val sentiments = new JListWrapper(annotation.get(classOf[CoreAnnotations.SentencesAnnotation]))
      .map { s =>
        val tree = s.get(classOf[SentimentCoreAnnotations.SentimentAnnotatedTree])
        RNNCoreAnnotations.getPredictedClass(tree)
      }
    sentiments.sum.toDouble / sentiments.length
  }
  else 0.0
}

object AccuracyTest {
  import Sentiment._
  import org.json4s._
  import org.json4s.native.Serialization.{read, write => swrite}

  implicit val formats = org.json4s.native.Serialization.formats(NoTypeHints)

  case class OutTweet(text: String, polarity: String, sentiment: Double)
  case class InTweet(text: String, polarity: String)

  // Remove the following from the sentence stream; they cause the lexer to issue
  // a warning:
  //  16/02/05 22:36:45 WARN PTBLexer: Untokenizable: ? (U+D83D, decimal: 55357)
  //  16/02/05 22:36:47 WARN PTBLexer: Untokenizable: ️ (U+FE0F, decimal: 65039)
  val badOnes = "\uFE0F|\uD83D|\uD83C".r

  // Process input file for sentiment
  def process(fileName: String): Unit = {
    val conf = new SparkConf()
      .setAppName("ErrorTest")
      .setMaster("local[*]")

    val Array(base, ext) = fileName.split("\\.")

    val sc = new SparkContext(conf)

    // Exit gracefully
    sys.addShutdownHook(sc.stop())

    sc.textFile(fileName)
      .map { line =>
        val inTweet = read[InTweet](line)
        val text = badOnes.replaceAllIn(inTweet.text, " ")
        assert(badOnes.findFirstIn(text).isEmpty)
        swrite(new OutTweet(text = text, polarity = inTweet.polarity, sentiment = analyze(text)))
      }
      .saveAsTextFile(s"$base-analyzed.$ext")

    sc.stop()
  }
}