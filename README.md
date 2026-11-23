# YTGrab

Android YouTube video/audio downloader (yt-dlp + ffmpeg + aria2c via youtubedl-android).

- Search by name or paste a link (video or playlist)
- Video (Best, 2160p ... 144p) or Audio (m4a / mp3 / opus, bitrate choice)
- Multi-threaded downloads (1-16 threads, optional aria2c) + several downloads in parallel
- Artwork / metadata embedded in every file
- Saved to Downloads/YTGrab

## Push from Termux

```sh
pkg install git unzip -y
unzip YTGrab.zip && cd YTGrab
git init -b main
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/twooooooh-boop/YTGrab.git
git push -u origin main
```

Create the empty repo `YTGrab` on github.com/twooooooh-boop first (no README).
Use a Personal Access Token (classic, `repo` + `workflow` scopes) as the password.

## Build

Every push builds automatically and publishes a Release (build-N) with one APK: `YTGrab-build-N.apk`.

The workflow installs Gradle itself, so no gradle wrapper is needed.
To add one for Android Studio: run `gradle wrapper --gradle-version 8.10.2` and commit the result.
