# podcaster
A podcast downloader written in Scala 3

## Features
1. Reads TOML file in the home directory `~/.podcasts.toml`  to get the config about which podcasts to download, and where to store the episodes. A sample TOML file `sample-podcasts.toml` is included.
2. Downloads the latest episodes of the podcasts.

### Coming soon

1. Remember downloaded podcasts in a file in the data directory so that we don't re-download even if the file was deleted.
2. Support checking and downloading selected podcasts.
3. Create a shell script to run the program.
4. Support Scala native.

## Usage
```
sbt run
```



