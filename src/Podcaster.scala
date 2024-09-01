package podcaster

import org.slf4j.{LoggerFactory, Logger}
import mainargs.{ParserForMethods, arg, main}
import upickle.default.{ReadWriter, Reader}
import os.Path
import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.collection.mutable.ArraySeq
import scala.util.{Failure, Success, Try}
import scala.xml.XML


object Podcaster {

  // Settings stored in "~/.podcasts.toml".
  case class Podcast(id: String, url: String) derives Reader
  case class Config(mediaDir: String) derives Reader
  case class Settings(config: Config, podcasts: Array[Podcast]) derives Reader

  // Representation of a podcast episode for our purposes.
  private case class Episode(title: String, url: String, pubDate: String)

  private val logger: Logger = LoggerFactory.getLogger("podcaster")

  // Parse the config from "~/.podcasts.json".
  private def parseSettings(): Try[Settings] = {
    val source = os.home / ".podcasts.json"
    println(s"Parsing the settings file $source")
    val contents = os.read(source)
    Try(upickle.default.read[Settings](contents))
  }

  // From the podcast feed in XML, extract the podcast episodes info
  private def extractFeed(xmlString: String): Seq[Episode] = {
    // convert the `String` to a `scala.xml.Elem`
    val xml = XML.loadString(xmlString)
    val rssItems = xml \ "channel" \ "item"
    for {
      i <- rssItems
      title = (i \ "title").text
      url = i \ "enclosure" \@ "url"
      pubDate = (i \ "pubDate").text
    } yield Episode(title, url, pubDate)
  }

  // Download the podcast episode
  private def downloadEpisode(podcastId: String, episode: Episode, podcastDirPath: Path): Future[String] = {
    val uri = new URI(episode.url)
    // Remove the query params and the fragment from the URI
    val downloadUri = new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath, null, null)
    val downloadFileName = Path(downloadUri.getPath).last
    val downloadPath = podcastDirPath / downloadFileName
    if !os.exists(downloadPath) then
      logger.info(s"$podcastId: downloading episode ${episode.title} published at ${episode.pubDate} from $downloadUri")
      Future { os.write(downloadPath, requests.get.stream(downloadUri.toString)) }.map (_ => downloadFileName)
    else
      logger.info(s"$podcastId: episode ${episode.title} already downloaded, skipping..")
      Future.successful(downloadFileName)
  }

  // Downloads <count> episodes of the podcast
  private def downloadEpisodes(podcastId: String, episodes: Seq[Episode], podcastDirPath: Path, countOfEpisodes: Int): Future[Seq[String]] = {
    // Start the episode download.
    // For each failed episode download, print the error, and move on.
    episodes.take(countOfEpisodes).foldLeft(Future.successful[Seq[String]](Nil)) {
      (acc, episode) => 
        acc.flatMap { paths =>
          downloadEpisode(podcastId, episode, podcastDirPath)
            .map { path => path +: paths }
            .recover {
              case e: Exception =>
                println(s"$podcastId: episode ${episode.title} could not be downloaded due to ${e.getMessage}")
                paths
            }
        }
    }
  }

  // Checks the podcast feed and returns episodes
  private def downloadPodcastFeed(podcastUrl: String): Future[Seq[Episode]] = {
    Future { requests.get(podcastUrl).text() }.map(contents => extractFeed(contents))
  }

  // Checks the podcast feed, and downloads the episodes
  private def downloadPodcast(podcast: Podcast, mediaDir: Path, countOfEpisodes: Int): Future[Seq[String]] = {
    logger.info(s"Downloading latest $countOfEpisodes episodes of ${podcast.id}")
    val podcastDirPath = mediaDir / podcast.id
    os.makeDir.all(podcastDirPath)
    downloadPodcastFeed(podcast.url)
      .flatMap(episodes => downloadEpisodes(podcast.id, episodes, podcastDirPath, countOfEpisodes))
      .recover {
        case e: Exception =>
          println(s"${podcast.id}: Got an error ${e.getMessage} while downloading podcasts")
          Nil
      }
      .map(paths => paths.map(_.toString))
  }


  // List the podcast feed
  private def showPodcast(podcast: Podcast, countOfEpisodes: Int): Future[Seq[String]] = {
    logger.info(s"Showing latest $countOfEpisodes episodes of ${podcast.id}")
    downloadPodcastFeed(podcast.url)
      .map(episodes => episodes.take(countOfEpisodes).map(e => s"\"${e.title}\" (published at ${e.pubDate})"))
  }

  // A utility method to process podcast config for both show and download actions.
  private def processPodcast(podcastIdOpt: Option[String], processPodcastEntry: (Podcast, Path) => Future[Seq[String]]): Future[ArraySeq[(String, Seq[String])]] = parseSettings() match {
    case Failure(e) => Future.failed(new RuntimeException(s"Failed to parse config $e"))
    case Success(settings) =>
      val mediaDir = Path(settings.config.mediaDir)
      val episodesFutures = settings.podcasts.map {
        podcast =>
          if podcastIdOpt.forall(_.equals(podcast.id)) then
            processPodcastEntry(podcast, mediaDir).map(es => (podcast.id, es))
          else
            Future.successful(("", Nil))
      }
      Future.sequence(episodesFutures)
  }

  @main
  def download(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to download")
    count: Int = 3): Unit = {
      val processPodcastEntry = (podcast: Podcast, mediaDir: Path) => downloadPodcast(podcast, mediaDir, count)
      val resultFuture = processPodcast(podcastIdOpt, processPodcastEntry).map {
        ps =>
          ps.foreach { presult =>
              val (podcastId, episodes) = presult
              val eresult = episodes.mkString(",")
              println(s"\n${podcastId}:\n${eresult}\n")
          }
      }
      Await.result(resultFuture, Duration.Inf)
  }

  @main
  def show(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to show")
    count: Int = 10): Unit = {
      val processPodcastEntry = (podcast: Podcast, mediaDir: Path) => showPodcast(podcast, count)
      val resultFuture = processPodcast(podcastIdOpt, processPodcastEntry).map {
        ps =>
          ps.foreach { presult =>
              val (podcastId, episodes) = presult
              val eresult = episodes.zipWithIndex.map((e, i) => s"${i+1}. ${e}").mkString("\n")
              println(s"\n${podcastId}:\n${eresult}\n")
          }
      }
      Await.result(resultFuture, Duration.Inf)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
