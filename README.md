# YTGrab

Android YouTube video/audio downloader built on the NewPipe extractor (the same engine NewPipe uses).

- Search by name or paste a video / playlist link
- Tap a result to pick Video or Audio, quality, and see the file size in MB
- Multi-threaded downloads (1-16 threads) + several downloads in parallel
- Cover art and title/artist are embedded in the finished mp4 / m4a files
- Saved to Downloads/YTGrab

Licensed GPL-3.0 because it uses NewPipeExtractor. The proof-of-origin ("PO token") step follows the
open-source BgUtils / NewPipe approach.

## Push from Termux

```sh
cd ~/YTGrab
git add -A && git commit -m "Update" && git push
```

Every push builds automatically and publishes a Release (build-N) with one APK: `YTGrab-build-N.apk`.
