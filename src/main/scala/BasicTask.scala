/**
  * Created by lucag on 2/2/16.
  */

import java.io._
import java.util.Properties

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations
import edu.stanford.nlp.pipeline.StanfordCoreNLP
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations
import kafka.producer.KeyedMessage
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.twitter._
import org.apache.spark.{Logging, SparkConf, SparkContext}
import twitter4j.{GeoLocation, Status, UserMentionEntity, User ⇒ TwitterUser}

//import scala.collection.immutable.Iterable
import scala.io.Source
import scala.language.postfixOps

object Config extends Logging {

  import Data._

  // Window timeframe duration
  val windowTime = Minutes(5)

  // How many items to report
  val topItemCount = 10

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
        case _           => Left(s"In $line: must be <path.to.key> = <value>")
      }
    }

    println("Pushing the following onto environment:")
    pairs.foreach {
      case Right((key, value)) =>
        System.setProperty(key, value)
        println(s"  $key = $value")
      case Left(message)       => println(s"Error: $message")
    }

  }

  // Kafka configuratipn
  val producerConfig = Map(
    "metadata.broker.list" -> "localhost:9092",
    "request.required.acks" -> "1",
    "serializer.class" -> "kafka.serializer.StringEncoder")

  def fromMap(ps: Map[String, Any]): Properties = (new Properties /: ps) {
    case (qs, (k, v: Int))    => qs.put(k, new Integer(v)); qs
    case (qs, (k, v: String)) => qs.put(k, v); qs
  }

  val kafkaConfig = fromMap(producerConfig)

  // Set reasonable logging levels
  def setStreamingLogLevels() {
    val log4jInitialized = Logger.getRootLogger.getAllAppenders.hasMoreElements
    if (!log4jInitialized) {
      // We first log something to initialize Spark's default logging, then we override the
      // logging level.
      logInfo(
        """Setting log level to [WARN] for streaming example.
          |To override add a custom log4j.properties to the classpath""")
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

  // The handles we want to extract (without the initial @)
  val airlineHandles = Set(Data.handles.map(_.substring(1)): _*)
}

// (Side-effecting) reporting procedures
object Reporting {

  // Serialize or report a few fields of a Status (Tweet) into a tab-separated record
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

  import spray.json.DefaultJsonProtocol

  object Kafka extends DefaultJsonProtocol {

    import Config.kafkaConfig
    import org.cloudera.spark.streaming.kafka.KafkaWriter._

    case class CountedString(count: Int, value: String)

    case class PolarizedText(tweet: Tweet, mention: String, cat: String, polarity: Double)

    case class User(id: Long, name: String)

    object User {
      def apply(user: TwitterUser) = new User(user.getId, user.getName)
    }

    case class Location(latitude: Double, longitude: Double)

    object Location {
      def apply(geoLocation: GeoLocation) = new Location(geoLocation.getLatitude, geoLocation.getLongitude)
    }

//    implicit object LocationJsonFormat extends RootJsonFormat[Location] {
//      override def write(obj: Location) = JsArray()
//
//      override def read(json: JsValue) = ???
//    }

    case class Tweet(id: Long, created_at: Long, text: String)

    object Tweet {
      def apply(status: Status) = new Tweet(
        status.getId,
        status.getCreatedAt.getTime,
//        if (status.getGeoLocation != null) Location(status.getGeoLocation) else null,
//        User(status.getUser),
        status.getText)
    }

    import spray.json._

    implicit object TweetJsonFormat extends RootJsonFormat[Tweet] {
      override def write(t: Tweet) = JsObject(
      "id" → JsNumber(t.id),
      "created_at" → JsNumber(t.created_at),
      "text" → JsString(t.text)
      )

      override def read(value: JsValue) = value match {
        case Seq(JsNumber(id), JsNumber(created_at), JsString(text)) ⇒
          Tweet(id.toLong, created_at.toLong, text)
        case _ ⇒ throw new DeserializationException("Tweet expected")
      }
    }


    implicit val countedValueFormat: JsonFormat[CountedString]      = jsonFormat2(CountedString)
    implicit val polarizedMessageFormat: JsonFormat[PolarizedText]  = jsonFormat4(PolarizedText)

    //    implicit val locationFormat: JsonFormat[Location]               = jsonFormat2(Location.apply)
    //    implicit val userFormat: JsonFormat[User]                       = jsonFormat2(User.apply)
    //    implicit val tweetFormat: JsonFormat[Tweet]                     = jsonFormat5(Tweet.apply)

    // Where top counts are enqueued
    def topCountsSerializer(p: (String, Int)) = p match {
      case (mention: String, count: Int) =>
        println(s"$mention: $count")
        new KeyedMessage("top-counts", mention, CountedString(count, mention).toJson.compactPrint)
    }

    // Collect 'user mentions' and counts in the <window> timeframe
    def enqueueSymbolsAndCounts(dstream: DStream[UserMentionEntity], window: Duration): Unit = {
      dstream
          .map(e => (e.getText, 1))                     // make them into pairs, as expected by reduceByKeyAndWindow
          .reduceByKeyAndWindow(_ + _, _ - _, window)   // sum over this window, subtracting the previous
          .foreachRDD(_.writeToKafka(kafkaConfig, topCountsSerializer))
    }

    val sentimentSerializer = (p: (String, Iterable[(Status, String, Double)])) => p match {
      case (mention: String, ps: Iterable[(Status, String, Double)]) ⇒
        val pts = for {
          (status, cat, polarity) ← ps.toSet
        } yield PolarizedText(Tweet(status), mention, cat, polarity)
        new KeyedMessage("sentiment", mention, pts.toJson.compactPrint)
    }

    import Data.airlineHandles

    def pickMention(status: Status) = {
      val mentions = status
                     .getUserMentionEntities
                     .map(_.getText)
                     .distinct
                     .filter(airlineHandles.contains)
      mentions.headOption.getOrElse("<unk>")
    }

    def enqueueSentimentByAirline(tweets: DStream[(String, Status)], window: Duration): Unit = {
      tweets
          .map { p =>
            val (mention, status) = p
            val text  = status.getText
            val sent  = Sentiment.analyze(text)
            val cat   = Sentiment.polarity(sent)
            (mention, (status, cat, sent))
          }
          .groupByKeyAndWindow(window) // group by polarity
          .foreachRDD(_.writeToKafka(kafkaConfig, sentimentSerializer))
    }
  }

  // File reporting: not a good example
  object File {
    // This implements the required function: find top counts (handles in this case) in a window
    // NOTE: | here and below this design is not optimal: in a real-world implementation,
    //       | the PrintWriter would be shared (with the constraint that it cannot be serialized
    //       | between driver and executors)
    def reportTopSymbols(dstream: DStream[UserMentionEntity], k: Int, window: Duration, fileName: String): Unit = {
      dstream
          .map { e => (e.getText, 1) }                        // make them into pairs, as expected by reduceByKeyAndWindow
          .reduceByKeyAndWindow(_ + _, _ - _, window)         // sum over this window, subtracting the previous
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
  }
}

object BasicTask {

  import Config._
  import Data._

  type ??? = Nothing

  // All the work is done here
  def run(): Unit = {
    Config.updateEnvironment()
    Config.setStreamingLogLevels()

    val log4j = s"-Dlog4j.configuration=${Data.baseDir}/etc/log4j.properties"

    println("Set Log to Warn")
    Logger.getRootLogger.setLevel(Level.WARN)

    // Create Spark context
    val conf = new SparkConf()
        .setAppName("TwitterSentimenter")
        .setExecutorEnv("spark.driver.extraJavaOptions", log4j)
        .setExecutorEnv("spark.executor.extraJavaOptions", log4j)

    val ssc = new StreamingContext(conf, Minutes(1))

    // Set checkpoint directory: needed for the window functions
    ssc.checkpoint(Data.baseDir + "/var/checkpoint")

    // These are the sources: it'll create $numStreams Twitter clients
    //
    //  NOTE:
    // -------------------------------------------------------------------------
    //    a long-running process with only 2 streams can overrun the rate limit!
    // -------------------------------------------------------------------------
    val numStreams = 1

    println(s"main: creating $numStreams streams")

    val twitterStreams = (1 to numStreams) map { _ ⇒
      TwitterUtils
          .createStream(ssc, None, Data.handles.toArray)
          .filter { s =>
            s.getLang match {
              case "en" => !s.isRetweet // English-only tweets, no retweets
              case _    => false
            }
          }
    }

    val tweets = ssc.union(twitterStreams)

    // Extract relevant user mentions
    val userMentions = tweets
        .flatMap(_.getUserMentionEntities) // extract and concatenate user mentions
        .filter(e => airlineHandles.contains(e.getText.toLowerCase()))

    val aboutAirlines = tweets flatMap { s ⇒
      val mentions = s.getUserMentionEntities.map(_.getText.toLowerCase())
      for (m ← mentions if airlineHandles.contains(m)) yield (m, s)
    }

    // Top 'user mentions,' i.e. airline names
    Reporting.Kafka.enqueueSymbolsAndCounts(userMentions, windowTime)

    // Tweets with sentiment in the current window
    Reporting.Kafka.enqueueSentimentByAirline(aboutAirlines, windowTime)

    // Start collection
    ssc.start()

    // Terminate gracefully on signals
    sys.addShutdownHook {
      print("\nmain: signal received, shutting down")
      ssc.stop()
      println("main: terminated")
    }

    // Exit after 5 hours anyway
    Thread.sleep(Minutes(5 * 60) milliseconds)
    ssc.stop()
  }

  def main(args: Array[String]) {
    run()
  }
}

object Sentiment {

  object Pipeline {
    def make(key: String, value: String) = {
      val props = new Properties
      props.setProperty(key, value)
      new StanfordCoreNLP(props)
    }
  }

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

  def analyze(sentence: String): Double =
    if (sentence.length > 0) {
      val sentiments = analyzeMany(sentence)
      sentiments.sum.toDouble / sentiments.length
    }
    else
      0.0

  def analyzeMany(sentence: String) = {
    val cleanedSentence = badOnes.replaceAllIn(sentence, " ")
    val annotation = pipeline.process(cleanedSentence)
    new JListWrapper(annotation.get(classOf[CoreAnnotations.SentencesAnnotation]))
    .map { s =>
      val tree = s.get(classOf[SentimentCoreAnnotations.SentimentAnnotatedTree])
      RNNCoreAnnotations.getPredictedClass(tree)
    }
  }
}

object AccuracyTest {

  import Sentiment._
  import org.json4s._
  import org.json4s.jackson.Serialization.{read, write ⇒ swrite}

  implicit val formats = org.json4s.jackson.Serialization.formats(NoTypeHints)

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

  object Semeval2015 {

    import resource._

    import scala.math.abs

    def polarity(sentimentValue: Double) =
      if (sentimentValue < 2.0)
        "negative"
      else if (2.0 <= sentimentValue && sentimentValue < 2.05)
        "neutral"
      else
        "positive"

    // We need to return the value of the most extreme sentence in text, which is the
    // one further away from 2.
    def analyze(sentence: String): Int =
      analyzeMany(sentence) reduceLeft {
        (overall: Int, sent: Int) =>
          if (abs(2 - sent) > abs(2 - overall))
            sent
          else
            overall
      }

    def process(fnInput: String, fnOutput: String): Unit = {
      for {
        input <- managed(Source.fromFile(fnInput))
        output <- managed(Data.fileAppender(fnOutput))
      } {
        input.getLines.foreach { line =>
          line.split("\\t") match {
            case Array(_, uid, actual, text) =>
              val predSent = Sentiment.analyze(text)
              val predPol = polarity(predSent)
              output.println(f"NA\t$uid\t$predPol\t$predSent%.2f\t$actual\t$text")
            case _                           => println(s"line $line: malformed")
          }
        }
      }
    }
  }

}
