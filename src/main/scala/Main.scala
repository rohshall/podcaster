import util.{Try, Success, Failure}
import scala.jdk.CollectionConverters.*
import scala.xml._
import sttp.client4._
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
def downloadEpisode(podcastId: String, episode: Episode, mediaDir: Path): Unit = {
  val downloadFileName = Paths.get(new URI(episode.url).getPath).getFileName
  val downloadPath = mediaDir.resolve(downloadFileName)
  if !Files.exists(downloadPath) then
    println(s"Downloading $podcastId episode ${episode.title} published at ${episode.pubDate} from ${episode.url}")
    val request = basicRequest.get(uri"${episode.url}")
                              .response(asPath(downloadPath))
    val backend = DefaultSyncBackend()
    val response = request.send(backend)
    if response.code.isSuccess then
      println("episode downloaded")
    else
      println("Failed to download")
  else
    println("File already downloaded, skipping..")
}

def downloadPodcast(podcastId: String, podcastUrl: String, mediaDir: Path): Unit = {
  val request = basicRequest.get(uri"$podcastUrl")
    .response(asString("utf-8"))
  val backend = DefaultSyncBackend()
  val response = request.send(backend)
  // response.body: by default read into an Either[String, String] to indicate failure or success 
  response.body match {
    case Right(xmlString) => {
      for episode <- extractFeed(xmlString).take(1)
      do downloadEpisode(podcastId, episode, mediaDir)
    }
    case Left(error) => println(s"Error downloading file: $error")
  }
}

@main def hello(): Unit = {
  parseConfig() match {
    case Failure(e) => println(s"Failed to parse config $e")
    case Success(config) => {
      if !Files.exists(config.mediaDir) then
        Files.createDirectory(config.mediaDir)
      for (podcastId, podcastUrl) <- config.podcastEntries
      do downloadPodcast(podcastId, podcastUrl, config.mediaDir)
    }
  }
}
