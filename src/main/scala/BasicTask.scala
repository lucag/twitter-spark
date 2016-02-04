/**
  * Created by lucag on 2/2/16.
  */

import org.apache.spark.SparkConf
import org.apache.spark.streaming.twitter._
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.immutable.Set
import scala.io.Source
import scala.language.postfixOps

object Data {
  def baseDir: String = sys.props("base.data.dir")
  def resource(name: String) = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name))
  def file(name: String) = Source.fromFile(baseDir + name)

  /** Side-effecting configuration of the Oauth Credentials for accessing Twitter */
  def updateEnvironment(): Unit = {
    val lines = resource("twitter.conf")
      .getLines
      .filter(_.trim.nonEmpty)
      .toSeq

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

  def handles: Seq[String] = file("/data/airline-twitter-sentiment/airline-handles")
    .getLines
    .map {
      _.split(",") match {
        case Array(_, v) => Some(v)
        case _ => None
      }
    }
    .collect { case Some(s) => s }
    .toSeq
}

object BasicTask {
  type *** = Any
  type ??? = Nothing

  // TODO
  def topHashTag(topic: String, k: Int): List[(Int, String)] = ???

  def test(): Unit = {
    // This is just a general test
    Data.updateEnvironment()

    val conf = new SparkConf()
      .setAppName("TwitterSentimenter")
      .setMaster("local[*]")
    val ssc = new StreamingContext(conf, Seconds(30))
    val tweets = TwitterUtils.createStream(ssc, None)

    // The handles we want to extract
    val relevantHandles = Set(Data.handles: _*)

    val
    // Select tweets by "relevant" handles
    val relevantTweets = tweets.filter { tweet =>
      tweet
        .getText
        .split("\\s+")
        .map { s =>
          println(s)
          s
        }
        .filter(_.startsWith("@"))
        .exists(relevantHandles.contains)
    }

    relevantTweets.print(100)

    ssc.start()
    ssc.awaitTermination()
  }

  def main(args: Array[String]) {
    test()
  }

}
