# podcaster
A podcast downloader written in Scala 3

## Features
1. Reads TOML file in the home directory `~/.podcasts.toml`  to get the config about which podcasts to download, and where to store the episodes. A sample TOML file `sample-podcasts.toml` is included.
2. Downloads the latest episode of the podcasts.

### Coming soon

1. Remember downloaded podcasts in a file in the data directory
2. Support command switches - sync, check, download (selected podcasts)
3. Create a shell script to run the program

## Usage
```
sbt run
```



