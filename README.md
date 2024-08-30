# podcaster
A podcast downloader written in Scala 3

## Features
1. Reads config file in the home directory `~/.podcasts.json`  to get the config about which podcasts to download, and where to store the episodes. A sample config file `sample-podcasts.json` is included.
2. Downloads the latest configurable number of episodes of the podcasts.

### Coming soon

1. Remember downloaded podcasts in a file in the data directory so that we don't re-download even if the file was deleted.
2. Create a shell script to run the program.
3. Support Scala native.

## Usage
```
mill run [show|download]
```



