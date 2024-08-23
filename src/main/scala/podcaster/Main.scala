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
  val episodes = for {
    i <- rssItems
    title = (i \ "title").text
    url = i \ "enclosure" \@ "url"
    pubDate = (i \ "pubDate").text
  } yield Episode(title, url, pubDate)
  episodes
}

// Download the podcast episode
def downloadEpisode(podcastId: String, episode: Episode, mediaDir: Path): Future[Either[String, Path]] = {
  val uri = new URI(episode.url)
  // Remove the query params and the fragment from the URI
  var downloadUri = new URI(
    uri.getScheme, // scheme
    null, // user info
    uri.getHost,
    uri.getPort,
    uri.getPath,
    null, // query
    null) // fragment

  var downloadFileName = uri.getPath.replaceAll("/", "_")
  var downloadPath = mediaDir.resolve(downloadFileName)
  if !Files.exists(downloadPath) then
    logger.debug(s"$podcastId: downloading episode ${episode.title} published at ${episode.pubDate} from $downloadUri")
    var request = basicRequest.get(uri"$downloadUri")
                              .response(asPath(downloadPath))
    request.send(backend).flatMap(response => {
      val redirectUrl = response.header(HeaderNames.Location)
      if response.code.isRedirect && redirectUrl.isDefined then
        logger.debug(s"$podcastId: redirecting to ${redirectUrl.get}")
        redirectUrl.map(url => {
          downloadUri = new URI(redirectUrl.get)
          // Generate a unique file name for the download
          // TODO: come up with a better way to generate the download file name
          downloadFileName = downloadUri.getPath.replaceAll("/", "_")
          downloadPath = mediaDir.resolve(downloadFileName)
          request = basicRequest.get(uri"$downloadUri")
                                .response(asPath(downloadPath))
          request.send(backend).map(_.body)
        }).getOrElse(Future { Left("No redirect location found") })
      else
        Future { response.body }
    })
  else
    logger.debug(s"$podcastId: episode ${episode.title} already downloaded, skipping..")
    Future { Right(downloadPath) }
}

def sequence[A, B](s: Seq[Either[A, B]]): Either[A, Seq[B]] =
  s.foldRight(Right(Nil): Either[A, List[B]]) {
    (e, acc) => for (xs <- acc; x <- e) yield x :: xs
  }

// Downloads <count> episodes of the podcast
def downloadEpisodes(podcastId: String, episodes: Seq[Episode], mediaDir: Path, count: Int = 1): Future[Either[String, Seq[Path]]] = {
  val episodeFutures = episodes
                          .take(count)
                          .map(episode => downloadEpisode(podcastId, episode, mediaDir))
  Future.sequence(episodeFutures).map(fs => sequence(fs))
}

// Checks the podcast feed and returns episodes
def downloadPodcastFeed(podcastUrl: String): Future[Either[String, Seq[Episode]]] = {
  val request = basicRequest.get(uri"$podcastUrl")
    .response(asString("utf-8"))
  request.send(backend).map(response => response.body.map(xmlString => extractFeed(xmlString)))
}

// Checks the podcast feed, and downloads the episodes
def downloadPodcast(podcastId: String, podcastUrl: String, mediaDir: Path): Future[Either[String, Seq[Path]]] = {
  downloadPodcastFeed(podcastUrl)
    .flatMap(feed => {
      // downloadResult is of type Either[String, Future[Either[String, Seq[Path]]]]
      // We need to return the type Future[Either[String, Seq[Path]]]
      val downloadResult = feed.map(episodes => downloadEpisodes(podcastId, episodes, mediaDir, 3))
      downloadResult match {
        case Left(s) => Future { Left(s) }
        case Right(r) => r
      }
    })
}


@main def podcaster(): Unit = {
  parseConfig() match {
    case Failure(e) => logger.debug(s"Failed to parse config $e")
    case Success(config) =>
      if !Files.exists(config.mediaDir) then
        Files.createDirectory(config.mediaDir)
      // podcastFutures is of type Seq[Future[Either[String, Seq[Path]]]]
      val podcastsFutures = config.podcastEntries
        .map((podcastId, podcastUrl) => downloadPodcast(podcastId, podcastUrl, config.mediaDir)
          .map {
            case Left(e) => logger.info(s"$podcastId: Got an error $e while downloading podcasts")
            case Right(ps) => logger.info(s"$podcastId: Check the files ${ps.map(_.getFileName).mkString(", ")}")
          })
      // resultFuture is of type Future[Seq[Either[String, Seq[Path]]]]
      val resultFuture = Future.sequence(podcastsFutures)
      Await.result(resultFuture, Duration.Inf)
  }
}
