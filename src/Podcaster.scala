package podcaster

import org.slf4j.{LoggerFactory, Logger}
import Console.{GREEN, RED, RESET, YELLOW, YELLOW_B, MAGENTA, MAGENTA_B, UNDERLINED}
import mainargs.{ParserForMethods, arg, main}
import upickle.default.Reader
import os.Path
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.XML


object Podcaster {

  val rfc2822Fmt = DateTimeFormatter.RFC_1123_DATE_TIME;

  // Representation of the settings stored in "~/.podcasts.json". We need to read the settings.
  case class Podcast(id: String, url: String) derives Reader
  case class Config(mediaDir: String) derives Reader
  case class AppSettings(config: Config, podcasts: Array[Podcast]) derives Reader

  // Representation of the state stored in "~/.podcaster_state.json". We need to both read and update the state.
  // Map[String, Set[String]]

  // Representation of a podcast episode for our purposes.
  // Technically, pubDate is optional as per podcast RSS feed specifications: https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification
  // But, I haven't seen podcasts which don't have pubDate for episodes.
  private case class Episode(title: String, url: String, guid: String, pubDate: LocalDateTime)

  private val logger: Logger = LoggerFactory.getLogger("podcaster")

  // Parse the config from "~/.podcasts.json".
  private def parseSettings(): Try[AppSettings] = {
    val source = os.home / ".podcasts.json"
    logger.info(s"Parsing the settings file $source")
    Try(os.read(source)).map(contents => upickle.default.read[AppSettings](contents))
  }

  // From the podcast feed in XML, extract the podcast episodes info
  // Currently, podcaster supports only "audio/mpeg" episodes, because that's what I am interested in.
  private def extractFeed(xmlString: String): Seq[Episode] = {
    // convert the `String` to a `scala.xml.Elem`
    val xml = XML.loadString(xmlString)
    val rssItems = xml \ "channel" \ "item"
    for {
      item <- rssItems
      title = (item \ "title").text
      guid = (item \ "guid").text
      audio_enc <- (item \ "enclosure").find(enc => enc \@ "type" == "audio/mpeg")
      url = audio_enc \@ "url"
      pubDate = rfc2822Fmt.parse((item \ "pubDate").text)
    } yield Episode(title, url, guid, LocalDateTime.from(pubDate))
  }

  // Download the podcast episode
  private def downloadEpisode(podcastId: String, episode: Episode, podcastDirPath: Path, episodesDownloaded: Set[String]): Future[Unit] = {
    val uri = new URI(episode.url)
    // Remove the query params and the fragment from the URI
    val downloadUri = new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath, null, null)
    val downloadFileName = Path(downloadUri.getPath).last
    val downloadPath = podcastDirPath / downloadFileName
    if !episodesDownloaded.contains(episode.guid) then
      logger.info(s"$podcastId: downloading episode \"${episode.title}\" published at ${episode.pubDate} from $downloadUri")
      Future { os.write.over(downloadPath, requests.get.stream(downloadUri.toString)) }
        .recover {
          case e: Exception =>
            logger.error(s"$podcastId: episode \"${episode.title}\" could not be downloaded due to ${e.getMessage}")
        }
    else
      logger.info(s"$podcastId: episode \"${episode.title}\" already downloaded, skipping..")
      Future.unit
  }

  // Checks the podcast feed and returns episodes
  private def downloadPodcastFeed(podcastUrl: String): Future[Seq[Episode]] = {
    Future { requests.get(podcastUrl).text() }.map(contents => extractFeed(contents))
  }

  // Checks the podcast feed, and downloads the episodes
  private def downloadPodcast(podcastId: String, episodes: Seq[Episode], mediaDir: Path, episodesDownloaded: Set[String]): Future[Unit] = {
    logger.info(s"Downloading latest episodes of ${podcastId}")
    val podcastDirPath = mediaDir / podcastId
    os.makeDir.all(podcastDirPath)
    Future.traverse(episodes)(episode => downloadEpisode(podcastId, episode, podcastDirPath, episodesDownloaded))
      .map { _ => 
        Console.printf("\nDownloaded for ")
        showPodcast(podcastId, episodes)
      }.recover {
        case e: Exception =>
        logger.error(s"${podcastId}: Got an error ${e.getMessage} while downloading podcast episodes")
      }
  }

  // List the podcast feed
  private def showPodcast(podcastId: String, episodes: Seq[Episode]): Unit = {
    Console.printf(s"${MAGENTA}${UNDERLINED}$podcastId${RESET}:\n")
    episodes.zipWithIndex.foreach { (e, i) =>
      val title = if e.title.length <= 70 then e.title else e.title.take(67) + "..."
      Console.printf(s"%2d. %-70s ${YELLOW}(%s)${RESET}\n", i+1, title, e.pubDate)
    }
  }

  // A utility method to process podcast config for both show and download actions.
  private def processPodcasts(podcastIdOpt: Option[String], processPodcastEntry: (String, Seq[Episode], Path) => Future[Unit]): Future[Unit] = parseSettings() match {
    case Failure(e) => Future.failed(new RuntimeException(s"Failed to parse config $e"))
    case Success(settings) =>
      val mediaDir = Path(settings.config.mediaDir)
      val episodesFutures = settings.podcasts.map {
        podcast =>
          if podcastIdOpt.forall(_.equals(podcast.id)) then
            downloadPodcastFeed(podcast.url)
              .flatMap(episodes => processPodcastEntry(podcast.id, episodes, mediaDir))
              .recover {
                case e: Exception =>
                  logger.error(s"${podcast.id}: Got an error ${e.getMessage} while fetching podcast feed")
              }
          else
            Future.unit
      }
      Future.sequence(episodesFutures).map(_.toArray)
  }

  // Update the state
  def updateAppState(appState: Map[String, Set[String]]): Try[Unit] = {
    val dest = os.home / ".podcaster_state.json"
    logger.info(s"Storing the state in $dest")
    val contents = upickle.default.write[Map[String, Set[String]]](appState, indent = 4, sortKeys = true)
    Try(os.write.over(dest, contents))
  }

  // Get the current app state, and if the file does not exist, return the default, empty state.
  def getCurrentAppState(): Map[String, Set[String]] = {
    val source = os.home / ".podcaster_state.json"
    logger.info(s"Getting the state from $source")
    Try(os.read(source)).map(contents => upickle.default.read[Map[String, Set[String]]](contents)).getOrElse(Map[String, Set[String]]())
  }

  // Utility method to combine the current state and the update.
  def combineSets[K, V](a: Map[K, Set[V]], b: TrieMap[K, Set[V]]): Map[K, Set[V]] = {
    a ++ b.map { case (k, v) => k -> (v ++ a.getOrElse(k, Set.empty)) }
  }

  @main
  def download(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to download")
    count: Int = 3): Unit = {
      val currentAppState : Map[String, Set[String]] = getCurrentAppState()
      // We will get the updates to be made in the current state in a thread-safe way.
      val appStateUpdate = TrieMap[String, Set[String]]()
      val processPodcastEntry = (podcastId: String, episodes: Seq[Episode], mediaDir: Path) => {
        val latestEpisodes = episodes.take(count)
        downloadPodcast(podcastId, latestEpisodes, mediaDir, currentAppState.get(podcastId).getOrElse(Set()))
          .andThen {
            case Success(_) => appStateUpdate.put(podcastId, latestEpisodes.map(_.guid).toSet)
          }
      }
      val resultFuture = processPodcasts(podcastIdOpt, processPodcastEntry)
      Await.result(resultFuture, 5.minutes)
      val updatedAppState = combineSets[String, String](currentAppState, appStateUpdate)
      updateAppState(updatedAppState) match {
        case Failure(e) => Future.failed(new RuntimeException(s"Failed to store app state $e"))
        case Success(_) => logger.info("App state updated")
      }
  }

  @main
  def show(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to show")
    count: Int = 10): Unit = {
      val processPodcastEntry = (podcastId: String, episodes: Seq[Episode], mediaDir: Path) => {
        logger.info(s"Showing latest episodes of ${podcastId}")
        showPodcast(podcastId, episodes.take(count))
        Future.unit
      }
      val resultFuture = processPodcasts(podcastIdOpt, processPodcastEntry)
      Await.result(resultFuture, 5.minutes)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
