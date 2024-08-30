package podcaster

import com.typesafe.scalalogging.Logger
import mainargs.{ParserForMethods, arg, main}
import upickle._
import upickle.default.{ReadWriter => RW, macroRW}
import sttp.client4.*
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.*

import java.net.URI
import java.net.http.*
import java.nio.file.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}
import scala.xml.*


object Podcaster {

  // Settings stored in "~/.podcasts.toml".
  case class Podcast(id: String, url: String)
  object Podcast{
    implicit val rw: RW[Podcast] = macroRW
  }
  case class Config(mediaDir: String)
  object Config{
    implicit val rw: RW[Config] = macroRW
  }
  case class Settings(config: Config, podcasts: Array[Podcast])
  object Settings{
    implicit val rw: RW[Settings] = macroRW
  }

  // Representation of a podcast episode for our purposes.
  private case class Episode(title: String, url: String, pubDate: String)

  private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
  private val backend: WebSocketBackend[Future] = HttpClientFutureBackend.usingClient(client)
  private val logger: Logger = Logger("podcaster")

  // Parse TOML config from "~/.podcasts.totl".
  private def parseSettings(): Try[Settings] = {
    val source = Paths.get(System.getProperty("user.home"), ".podcasts.json")
    println(s"Parsing the settings file $source")
    Try(upickle.default.read[Settings](source))
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

  // Handle 302 redirect responses while getting responses. As per the standard, we need to explicitly decide to follow the redirect URL.
  private def handle302Response(redirectUrl: Option[String], podcastId: String, podcastDirPath: Path): Future[Path] = {
    redirectUrl.map(url => {
      logger.info(s"$podcastId: redirecting to $url")
      val downloadUri = new URI(url)
      val downloadFileName = Paths.get(downloadUri.getPath).getFileName
      val downloadPath = podcastDirPath.resolve(downloadFileName)
      val request = basicRequest
        .get(uri"$downloadUri")
        .response(asPath(downloadPath))
        for response <- request.send(backend)
          yield response.body match
          case Right(body) => body
          case Left(s) => throw new RuntimeException(s)
    }).getOrElse(Future.failed(throw new RuntimeException("No redirect URL!")))
  }

  // Process episode download response
  private def processDownloadResponse(response: Response[Either[String, Path]], podcastId: String, podcastDirPath: Path): Future[Path] = {
    if response.code.isRedirect then
      val redirectUrl = response.header(HeaderNames.Location)
      handle302Response(redirectUrl, podcastId, podcastDirPath)
    else
      response.body match
        case Right(body) => Future.successful(body.getFileName)
        case Left(s) => Future.failed(throw new RuntimeException(s))
  }

  // Download the podcast episode
  private def downloadEpisode(podcastId: String, episode: Episode, podcastDirPath: Path): Future[Path] = {
    val uri = new URI(episode.url)
    // Remove the query params and the fragment from the URI
    val downloadUri = new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath, null, null)
    val downloadFileName = Paths.get(downloadUri.getPath).getFileName
    val downloadPath = podcastDirPath.resolve(downloadFileName)
    if !Files.exists(downloadPath) then
      logger.info(s"$podcastId: downloading episode ${episode.title} published at ${episode.pubDate} from $downloadUri")
      val request = basicRequest.get(uri"$downloadUri").response(asPath(downloadPath))
      for
        response <- request.send(backend)
        file <- processDownloadResponse(response, podcastId, podcastDirPath)
      yield file
    else
      logger.info(s"$podcastId: episode ${episode.title} already downloaded, skipping..")
      Future.successful(downloadFileName)
  }

  // Downloads <count> episodes of the podcast
  private def downloadEpisodes(podcastId: String, episodes: Seq[Episode], podcastDirPath: Path, countOfEpisodes: Int): Future[Seq[Path]] = {
    // Start the episode download.
    // For each failed episode download, print the error, and move on.
    episodes.take(countOfEpisodes).foldLeft(Future.successful[Seq[Path]](Nil)) {
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
    val request = basicRequest.get(uri"$podcastUrl").response(asString("utf-8"))
    for response <- request.send(backend)
      yield response.body match
      case Right(xmlString) => extractFeed(xmlString)
      case Left(s) => throw new RuntimeException(s)
  }

  // Checks the podcast feed, and downloads the episodes
  private def downloadPodcast(podcast: Podcast, mediaDir: Path, countOfEpisodes: Int): Future[Unit] = {
    println(s"Downloading latest $countOfEpisodes episodes of ${podcast.id}")
    val podcastDirPath = mediaDir.resolve(podcast.id)
    if !Files.exists(podcastDirPath) then Files.createDirectories(podcastDirPath)
    downloadPodcastFeed(podcast.url)
      .flatMap(episodes => downloadEpisodes(podcast.id, episodes, podcastDirPath, countOfEpisodes))
      .recover {
        case e: Exception =>
          println(s"${podcast.id}: Got an error ${e.getMessage} while downloading podcasts")
          Nil
      }
      .map { paths =>
        if paths.nonEmpty then
          println(s"${podcast.id}: Check the files ${paths.mkString(", ")}")
        else
          println(s"${podcast.id}: No files downloaded")
      }
  }


  // List the podcast feed
  private def showPodcast(podcast: Podcast, countOfEpisodes: Int): Future[Unit] = {
    println(s"Showing latest $countOfEpisodes episodes of ${podcast.id}")
    downloadPodcastFeed(podcast.url)
      .map(episodes => println(s"${podcast.id} -->\n" + episodes.take(countOfEpisodes).zipWithIndex.map((e, i) => s"${i+1}. ${e.title} (published at ${e.pubDate})").mkString("\n")))
  }

  // A utility method to process podcast config for both show and download actions.
  private def processPodcast(podcastIdOpt: Option[String], processPodcastEntry: (Podcast, Path) => Future[Unit]): Future[Unit] = parseSettings() match {
    case Failure(e) => Future.failed(new RuntimeException(s"Failed to parse config $e"))
    case Success(settings) =>
      val mediaDir = Paths.get(settings.config.mediaDir)
      Future.traverse(settings.podcasts) {
        podcast => if podcastIdOpt.forall(_.equals(podcast.id)) then
                    processPodcastEntry(podcast, mediaDir)
                   else
                    Future.unit
      }.map(_ => ())
  }

  @main
  def download(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to download")
    count: Int = 3): Unit = {
      val processPodcastEntry = (podcast: Podcast, mediaDir: Path) => downloadPodcast(podcast, mediaDir, count)
      val resultFuture = processPodcast(podcastIdOpt, processPodcastEntry)
      Await.result(resultFuture, Duration.Inf)
  }

  @main
  def show(@arg(short = 'p', doc = "podcast ID, which identifies the podcast")
    podcastIdOpt: Option[String],
    @arg(short = 'c', doc = "count of latest podcast episodes to show")
    count: Int = 10): Unit = {
      val processPodcastEntry = (podcast: Podcast, mediaDir: Path) => showPodcast(podcast, count)
      val resultFuture = processPodcast(podcastIdOpt, processPodcastEntry)
      Await.result(resultFuture, Duration.Inf)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
