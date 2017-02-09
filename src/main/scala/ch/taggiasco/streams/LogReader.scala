package ch.taggiasco.streams

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.util.{Try, Failure, Success}
import akka.NotUsed


object LogReader {

  def main(args: Array[String]): Unit = {
    // actor system and implicit materializer
    implicit val system = ActorSystem("system")
    implicit val materializer = ActorMaterializer()
    
    val LogPattern = """(.*) Timing: ([A-Z]+) (.+) took ([0-9]+)ms and returned ([0-9]+)""".r
    
    // get log levels from arguments, or default
    val levels = Try {
      args(1).split("-").map(Integer.parseInt)
    }.toOption.getOrElse(TimingGroup.defaultGroups)
    val groups = (0 +: levels).zipAll( (levels :+ 0), -1, -1 )
    // functions
    val groupCreator: (LogEntry => Int) = TimingGroup.group(groups, _)
    val groupLabel: (Int => String) = TimingGroup.getLabel(groups, _)
    
    // read lines from a log file
    val logFile = Paths.get("src/main/resources/" + args(0))

    FileIO.fromPath(logFile).
      // parse chunks of bytes into lines
      via(Framing.delimiter(ByteString(System.lineSeparator), maximumFrameLength = 512, allowTruncation = true)).
      map(_.utf8String).
      collect {
        case line @ LogPattern(date, httpMethod, url, timing, status) =>
          LogEntry(date, httpMethod, url, Integer.parseInt(timing), Integer.parseInt(status), line)
      }.
      // group them by log level
      groupBy(levels.size + 1, groupCreator).
      fold((0, List.empty[LogEntry])) {
        case ((_, list), logEntry) => (groupCreator(logEntry), logEntry :: list)
      }.
      // write lines of each group to a separate file
      mapAsync(parallelism = levels.size + 1) {
        case (level, groupList) =>
          println(groupLabel(level) + " : " + groupList.size + " requests")
          Source(groupList.reverse).map(entry => ByteString(entry.line + "\n")).runWith(FileIO.toPath(Paths.get(s"target/log-${groupLabel(level)}.txt")))
      }.
      mergeSubstreams.
      runWith(Sink.onComplete {
        case Success(_) =>
          system.terminate()
        case Failure(e) =>
          println(s"Failure: ${e.getMessage}")
          system.terminate()
      })
    
    
    
    
    val source =
      FileIO.fromPath(logFile).
      via(Framing.delimiter(ByteString(System.lineSeparator), maximumFrameLength = 512, allowTruncation = true)).
      map(_.utf8String).
      collect {
        case line @ LogPattern(date, httpMethod, url, timing, status) =>
          LogEntry(date, httpMethod, url, Integer.parseInt(timing), Integer.parseInt(status), line)
      }
    
    
    def reduceByKey[In, K, Out](
      maximumGroupSize: Int,
      groupKey:         (In) => K,
      map:              (In) => Out
    )(reduce: (Out, Out) => Out): Flow[In, (K, Out), NotUsed] = {
      Flow[In]
        .groupBy[K](maximumGroupSize, groupKey)
        .map(e => groupKey(e) -> map(e))
        .reduce((l, r) => l._1 -> reduce(l._2, r._2))
        .mergeSubstreams
    }
    
    
    
    
    
  }
}