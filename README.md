# podcaster
A podcast downloader written in Java

## Features
1. Reads TOML file in the home directory `~/.podcasts.toml`  to get the config about which podcasts to download, and where to store the episodes. A sample TOML file `sample-podcasts.toml` is included.
2. Does not download any episode unless specifically instructed to.

### Coming soon

1. Create a shell script to run the program
2. Support command switches
3. Show listing - local and remote episodes
4. Download episodes using chapter numbers
5. Cleanup episodes.
6. Play the episode

## Usage
```
mvn exec:java -Dexec.args="TheBulwark"
```

It will download the latest episode of the "TheBulwark" podcast.


