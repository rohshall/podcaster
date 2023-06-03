package com.salquestfl.podcaster;

import java.io.IOException;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Collectors;
import com.apptasticsoftware.rssreader.Enclosure;
import com.apptasticsoftware.rssreader.Item;
import com.apptasticsoftware.rssreader.RssReader;
import org.tomlj.Toml;
import org.tomlj.TomlTable;
import org.tomlj.TomlParseResult;

/**
 * A podcast downloader
 * 
 * Last updated 5/16/2023.
 * 
 * @author Salil Wadnerkar
 * @version 1.0
 */

public class App {

    private static class Config {
        final String mediaDir;
        final Map<String, String> podcasts;
        
        private Config(String mediaDir, Map<String, String> podcasts) {
            this.mediaDir = mediaDir;
            this.podcasts = podcasts;
        }
        
        public static Config getConfig() throws IOException {
            Path source = Paths.get(System.getProperty("user.home"), ".podcasts.toml");
            TomlParseResult result = Toml.parse(source);
            result.errors().forEach(error -> System.err.println(error.toString()));

            String mediaDir = result.getString("config.media_dir");
            TomlTable podcastTable = result.getTable("podcasts");
            Map<String, String> podcasts = new HashMap<>();
            for (String podKey : podcastTable.keySet()) {
                podcasts.put(podKey, podcastTable.getString(podKey));
            }
            return new Config(mediaDir, podcasts);
        }
    }


    private static class PodcastDownloader {
        private RssReader rssReader = new RssReader();

        Optional<Path> downloadFromUrl(Path podcastDir, URL downloadUrl) {
            Path filePath = Paths.get(podcastDir.toString(), Paths.get(downloadUrl.getPath()).getFileName().toString());
            System.out.printf("Downloading %s to %s%n", downloadUrl, filePath);
            try (ReadableByteChannel readableByteChannel = Channels.newChannel(downloadUrl.openStream());
                    FileOutputStream fileOutputStream = new FileOutputStream(filePath.toFile());) {
                FileChannel fileChannel = fileOutputStream.getChannel();
                fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                return Optional.of(filePath);
                    }
            catch (IOException e) {
                System.err.println(e);
                return Optional.empty();
            }
        }
        // podcastDir must exist
        Optional<Path> downloadLatestChapter(Path podcastDir, String podcastUrl) {
            try {
                return rssReader.read(podcastUrl)
                    .flatMap(item -> item.getEnclosure().stream())
                    .map(Enclosure::getUrl)
                    .findFirst()
                    .flatMap(latestEpisode -> {
                        try {
                            URL downloadUrl = new URL(latestEpisode);
                            return downloadFromUrl(podcastDir, downloadUrl);
                        } catch (MalformedURLException e) {
                            System.err.println(e);
                            return Optional.empty();
                        }
                    });
            } catch (IOException e) {
                System.err.println(e);
                return Optional.empty();
            }
        }
    }

    private final Config config;

    public App(Config config) {
        this.config = config;
    }

    public void downloadPodcastLatest(String podKey) {
        Path podcastDir = Paths.get(config.mediaDir, podKey);
        try {
            Files.createDirectories(podcastDir);
        } catch (IOException e) {
            System.err.println(e);
        }
        System.out.println("Downloading " + podKey);
        String podcastUrl = config.podcasts.get(podKey);
        if (podcastUrl == null) {
            System.err.println("Invalid podcast key " + podKey + "!");
            return;
        }
        PodcastDownloader downloader = new PodcastDownloader();
        downloader.downloadLatestChapter(podcastDir, podcastUrl).ifPresent(path -> {
            System.out.println("Downloaded the episode at " + path);
        });
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.getConfig();
        App app = new App(config);
        app.downloadPodcastLatest(args[0]);
    }
}
