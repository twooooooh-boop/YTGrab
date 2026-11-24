package com.ytgrab.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** YouTube access through the NewPipe extractor: search, stream list, download, mux, tag. */
object Engine {
    val status = MutableStateFlow("Starting engine…")
    val ready = MutableStateFlow(false)

    fun init(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NewPipe.init(NpDownloader(), Localization.DEFAULT, ContentCountry.DEFAULT)
                YoutubeStreamExtractor.setPoTokenProvider(WebPoTokenProvider(app.applicationContext))
                ready.value = true
                status.value = "Engine ready (NewPipe extractor)"
            } catch (e: Throwable) {
                status.value = "Engine failed to start: ${e.message}"
            }
        }
    }

    // ---------- search ----------

    private fun StreamInfoItem.toItem() = SearchItem(
        id = url,
        title = name ?: "(untitled)",
        channel = uploaderName ?: "",
        durationSec = duration.coerceAtLeast(0),
        url = url,
        thumb = thumbnails.maxByOrNull { it.height }?.url ?: "",
    )

    private fun StreamInfo.toItem() = SearchItem(
        id = url,
        title = name ?: "(untitled)",
        channel = uploaderName ?: "",
        durationSec = duration.coerceAtLeast(0),
        url = url,
        thumb = thumbnails.maxByOrNull { it.height }?.url ?: "",
    )

    suspend fun search(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        if (!ready.value) throw IllegalStateException(status.value)
        val q = query.trim()
        val svc = ServiceList.YouTube
        if (q.startsWith("http://") || q.startsWith("https://")) {
            when (svc.getLinkTypeByUrl(q)) {
                StreamingService.LinkType.STREAM -> {
                    val info = StreamInfo.getInfo(q)
                    StreamCache.put(info.url, info)
                    StreamCache.put(q, info)
                    listOf(info.toItem())
                }
                StreamingService.LinkType.PLAYLIST -> {
                    val pl = PlaylistInfo.getInfo(svc, q)
                    pl.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toItem() }
                }
                else -> throw IllegalStateException("Paste a video or playlist link")
            }
        } else {
            val handler = svc.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
            val info = SearchInfo.getInfo(svc, handler)
            info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toItem() }
        }
    }

    suspend fun streamInfo(url: String): StreamInfo = withContext(Dispatchers.IO) {
        if (!ready.value) throw IllegalStateException(status.value)
        StreamCache.get(url) ?: StreamInfo.getInfo(url).also { StreamCache.put(url, it) }
    }

    // ---------- download ----------

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(110).ifBlank { "video" }

    private fun httpBytes(url: String): ByteArray? = try {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", Http.UA).build()
        Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (e: Exception) {
        null
    }

    /** Best thumbnail as JPEG bytes (optionally cropped to a square). */
    private fun fetchCover(info: StreamInfo, square: Boolean, quality: Int): ByteArray? {
        val urls = ArrayList<String>()
        info.thumbnails.sortedByDescending { it.height }.forEach { urls.add(it.url) }
        urls.add("https://i.ytimg.com/vi/${info.id}/maxresdefault.jpg")
        urls.add("https://i.ytimg.com/vi/${info.id}/hqdefault.jpg")
        for (u in urls.distinct().take(5)) {
            val bytes = httpBytes(u) ?: continue
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            if (square) {
                val side = minOf(bmp.width, bmp.height)
                bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            return out.toByteArray()
        }
        return null
    }

    private class Progress(val onProgress: (Float, Long) -> Unit) {
        val done = AtomicLong(0)
        @Volatile var total = 1L
        private val started = System.currentTimeMillis()

        fun add(n: Long) {
            val d = done.addAndGet(n).coerceAtLeast(0)
            val frac = (d.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.92f
            val secs = (System.currentTimeMillis() - started) / 1000.0
            val eta = if (secs > 2 && d > 0) ((total - d) / (d / secs)).toLong().coerceAtLeast(0) else 0L
            onProgress(frac, eta)
        }
    }

    private fun fetchFile(url: String, expected: Long, dest: File, threads: Int, token: CancelToken, p: Progress): Long {
        val len = if (expected > 0) expected else ChunkDownloader.probeLength(url)
        ChunkDownloader.download(url, len, dest, threads, token, Prefs.chunkMb * 1024L * 1024L) { p.add(it) }
        return len
    }

    private fun streamBytesOf(s: org.schabi.newpipe.extractor.stream.Stream): Long {
        val n = s.itagItem?.contentLength ?: return 0L
        return if (n > 0) n else 0L
    }

    private fun fileNameFor(info: StreamInfo, safeTitle: String, qualityTag: String): String {
        val artist = (info.uploaderName ?: "").removeSuffix(" - Topic")
        val title = info.name ?: safeTitle
        return when (Prefs.nameTemplate) {
            "artist_title" -> if (artist.isBlank()) safeTitle else sanitize("$artist - $title")
            "title_quality" -> sanitize("$title [$qualityTag]")
            else -> safeTitle
        }
    }

    /** Blocking. Returns a message for the queue. Throws on failure or cancel. */
    fun download(
        ctx: Context,
        t: DownloadTask,
        token: CancelToken,
        onProgress: (Float, Long) -> Unit,
    ): String {
        if (Prefs.wifiOnly) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (cm != null && cm.isActiveNetworkMetered) {
                throw IllegalStateException("Wi-Fi only is on, but you're on mobile data")
            }
        }
        val info = StreamCache.get(t.item.url) ?: StreamInfo.getInfo(t.item.url).also { StreamCache.put(t.item.url, it) }
        val opts = buildOptions(info)
        val o = t.options
        val folder = Prefs.cleanFolder(Prefs.folder).trim().ifBlank { "YTGrab" }
        val tmp = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp/${t.id}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val safe = sanitize(info.name ?: t.item.title)
            val progress = Progress(onProgress)
            val outFile: File
            var isWebm = false

            if (o.mode == Mode.AUDIO) {
                val a = pickAudio(opts, o.audioBitrate) ?: throw IllegalStateException("No audio stream found")
                val raw = File(tmp, "audio.src")
                progress.total = a.bytes.coerceAtLeast(1)
                fetchFile(a.stream.content, a.bytes, raw, o.threads, token, progress)
                token.check()
                outFile = File(tmp, fileNameFor(info, safe, "${a.kbps}k") + ".m4a")
                Muxer.mux(null, raw, outFile, false, token)
            } else {
                val v = pickVideo(opts, o.videoHeight) ?: throw IllegalStateException("No video stream found")
                isWebm = v.webm
                val ext = if (v.webm) "webm" else "mp4"
                outFile = File(tmp, fileNameFor(info, safe, "${v.height}p") + "." + ext)
                if (v.audio == null) {
                    progress.total = v.bytes.coerceAtLeast(1)
                    fetchFile(v.video.content, v.bytes, outFile, o.threads, token, progress)
                } else {
                    val vf = File(tmp, "video.src")
                    val af = File(tmp, "audio.src")
                    progress.total = v.bytes.coerceAtLeast(1)
                    val vl = fetchFile(v.video.content, streamBytesOf(v.video), vf, o.threads, token, progress)
                    val al = fetchFile(v.audio.content, streamBytesOf(v.audio), af, o.threads, token, progress)
                    progress.total = (vl + al).coerceAtLeast(1)
                    token.check()
                    Muxer.mux(vf, af, outFile, v.webm, token)
                }
            }
            onProgress(0.95f, 0)
            token.check()

            // artwork + tags (each can be switched off in Settings)
            val wantArt = if (o.mode == Mode.AUDIO) Prefs.artAudio else Prefs.artVideo
            val wantTags = Prefs.embedTags
            var artworkOk = false
            if (!isWebm && (outFile.extension == "mp4" || outFile.extension == "m4a") && (wantArt || wantTags)) {
                val cover = if (wantArt) fetchCover(info, o.mode == Mode.AUDIO && Prefs.squareCover, Prefs.coverQuality) else null
                val artist = (info.uploaderName ?: "").removeSuffix(" - Topic")
                val ok = Mp4Tagger.tag(
                    outFile,
                    if (wantTags) (info.name ?: safe) else "",
                    if (wantTags) artist else "",
                    cover,
                )
                artworkOk = ok && cover != null
            }
            token.check()
            saveToDownloads(ctx, outFile, folder)
            return when {
                artworkOk -> "Saved to Downloads/$folder (with artwork)"
                !wantArt -> "Saved to Downloads/$folder"
                isWebm -> "Saved to Downloads/$folder (WebM can't hold artwork here)"
                else -> "Saved to Downloads/$folder (artwork could not be embedded)"
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun clearTemp(ctx: Context) {
        File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp").deleteRecursively()
    }

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun saveToDownloads(ctx: Context, f: File, folder: String) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(f.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
