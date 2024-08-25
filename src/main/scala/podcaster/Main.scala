package podcaster

import com.typesafe.scalalogging.Logger
import org.tomlj.*
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

case class Config(mediaDir: Path, podcastEntries: Seq[(String, String)])

case class Episode(title: String, url: String, pubDate: String)

val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()  
val backend: WebSocketBackend[Future] = HttpClientFutureBackend.usingClient(client)
val logger: Logger = Logger("podcaster")

// Parse TOML config
def parseConfig(): Try[Config] = {
  val source = Paths.get(System.getProperty("user.home"), ".podcasts.toml")
  val result = Toml.parse(source)
  if result.hasErrors then
    Failure(RuntimeException(result.errors.asScala.map(_.toString).mkString("\n")))
  else
    val mediaDir = result.getString("config.media_dir")
    val podcastTable = result.getTable("podcasts")
    val podcastEntries = for podKey <- podcastTable.keySet.asScala yield (podKey, podcastTable.getString(podKey))
    Success(Config(Paths.get(mediaDir), podcastEntries.toSeq))
}

// From the feed in XML, extract the podcast episodes info
def extractFeed(xmlString: String): Seq[Episode] = {
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
def handle302Response(redirectUrl: Option[String], podcastId: String, podcastDirPath: Path): Future[Path] = {
  redirectUrl.map(url => {
    logger.debug(s"$podcastId: redirecting to $url")
    val downloadUri = new URI(url)
    val downloadFileName = Paths.get(downloadUri.getPath).getFileName
    val downloadPath = podcastDirPath.resolve(downloadFileName)
    val request = basicRequest
      .get(uri"${downloadUri}")
      .response(asPath(downloadPath))
    for response <- request.send(backend)
      yield response.body match {
        case Right(body) => body
        case Left(s) => throw new RuntimeException(s)
      }
  }).getOrElse(Future { throw new RuntimeException("No redirect URL!") })
}

// Download the podcast episode
def downloadEpisode(podcastId: String, episode: Episode, podcastDirPath: Path): Future[Path] = {
  val uri = new URI(episode.url)
  // Remove the query params and the fragment from the URI
  val downloadUri = new URI(uri.getScheme, null, uri.getHost, uri.getPort, uri.getPath, null, null)
  val downloadFileName = Paths.get(downloadUri.getPath).getFileName
  val downloadPath = podcastDirPath.resolve(downloadFileName)
  if !Files.exists(downloadPath) then
    logger.debug(s"$podcastId: downloading episode ${episode.title} published at ${episode.pubDate} from $downloadUri")
    var request = basicRequest
      .get(uri"$downloadUri")
      .response(asPath(downloadPath))
    request.send(backend).flatMap {
      response => if response.code.isRedirect then handle302Response(response.header(HeaderNames.Location), podcastId, podcastDirPath)
                  else Future {
                    response.body match {
                      case Right(body) => body
                      case Left(s) => throw new RuntimeException(s)
                    }
                  }
    }
  else
    logger.debug(s"$podcastId: episode ${episode.title} already downloaded, skipping..")
    Future { downloadPath }
}

// Downloads <count> episodes of the podcast
def downloadEpisodes(podcastId: String, episodes: Seq[Episode], podcastDirPath: Path, count: Int = 1): Future[Seq[Path]] = {
  Future.traverse(episodes.take(count)) {
    episode => downloadEpisode(podcastId, episode, podcastDirPath).recover {
      case e: Exception => {
        logger.error(s"$podcastId: episode ${episode.title} could not be downloaded due to ${e.getMessage}")
        null
      }
    }
  }
}

// Checks the podcast feed and returns episodes
def downloadPodcastFeed(podcastUrl: String): Future[Seq[Episode]] = {
  val request = basicRequest
    .get(uri"$podcastUrl")
    .response(asString("utf-8"))
  request.send(backend).map(response => response.body match {
    case Right(xmlString) => extractFeed(xmlString)
    case Left(s) => throw new RuntimeException(s)
  })
}

// Checks the podcast feed, and downloads the episodes
def downloadPodcast(podcastId: String, podcastUrl: String, mediaDir: Path): Future[Seq[Path]] = {
  val podcastDirPath = mediaDir.resolve(podcastId)
  if !Files.exists(podcastDirPath) then Files.createDirectory(podcastDirPath)
  downloadPodcastFeed(podcastUrl) flatMap {
    episodes => downloadEpisodes(podcastId, episodes, podcastDirPath, 3)
      .filter(p => p != null)
  }
}


@main def podcaster(): Unit = parseConfig() match {
  case Failure(e) => logger.debug(s"Failed to parse config $e")
  case Success(config) =>
    if !Files.exists(config.mediaDir) then Files.createDirectory(config.mediaDir)
    val resultFuture = Future.traverse(config.podcastEntries) {
      (podcastId, podcastUrl) => downloadPodcast(podcastId, podcastUrl, config.mediaDir)
        .map { paths => logger.info(s"$podcastId: Check the files ${paths.map(_.getFileName).mkString(", ")}") }
        .recover { case e: Exception => {
            logger.error(s"$podcastId: Got an error ${e.getMessage} while downloading podcasts")
            ""
          }
        }
    }
    Await.result(resultFuture, Duration.Inf)
}
