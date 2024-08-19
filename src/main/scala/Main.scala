import util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.xml._
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import java.net.URI
import java.nio.file._
import org.tomlj._

case class Config(mediaDir: Path, podcastEntries: Seq[(String, String)])

case class Episode(title: String, url: String, pubDate: String)


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
  val rssItems = (xml \ "channel" \ "item")
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
  val downloadFileName = Paths.get(new URI(episode.url).getPath).getFileName
  val downloadPath = mediaDir.resolve(downloadFileName)
  if !Files.exists(downloadPath) then
    println(s"$podcastId: downloading episode ${episode.title} published at ${episode.pubDate} from ${episode.url}")
    val request = basicRequest.get(uri"${episode.url}")
                              .response(asPath(downloadPath))
    val backend = HttpClientFutureBackend()
    request.send(backend).map(response => response.body)
  else
    println(s"$podcastId: episode ${episode.title} already downloaded, skipping..")
    Future { Right(downloadPath) }
}

def sequence[A, B](s: Seq[Either[A, B]]): Either[A, Seq[B]] =
  s.foldRight(Right(Nil): Either[A, List[B]]) {
    (e, acc) => for (xs <- acc.right; x <- e.right) yield x :: xs
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
  val backend = HttpClientFutureBackend()
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
    case Failure(e) => println(s"Failed to parse config $e")
    case Success(config) => {
      if !Files.exists(config.mediaDir) then
        Files.createDirectory(config.mediaDir)
      // podcastFutures is of type Seq[Future[Either[String, Seq[Path]]]]
      val podcastsFutures = config.podcastEntries
        .map((podcastId, podcastUrl) => downloadPodcast(podcastId, podcastUrl, config.mediaDir)
            .map(podcastResult => podcastResult match {
              case Left(e) => println(s"$podcastId: Got an error $e while downloading podcasts")
              case Right(ps) => println(s"$podcastId: Check the files ${ps.map(_.getFileName).mkString(", ")}")
          }))
      // resultFuture is of type Future[Seq[Either[String, Seq[Path]]]]
      val resultFuture = Future.sequence(podcastsFutures)
      Await.result(resultFuture, Duration.Inf)
    }
  }
}
