package com.ytgrab.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What is needed to ask YouTube for the next page of the same search. */
class MoreToken(val handler: SearchQueryHandler, val page: Page)

/** One page of search results plus what YouTube said about the query. */
class SearchPage(
    val items: List<SearchItem>,
    val corrected: String? = null,     // "Showing results for ..."
    val didYouMean: String? = null,    // "Did you mean ...?"
    val more: MoreToken? = null,
)

/** YouTube access through the NewPipe extractor: search, stream list, download, mux, tag. */
object Engine {
    val status = MutableStateFlow("Starting engine…")
    val ready = MutableStateFlow(false)
    @Volatile private var poProvider: WebPoTokenProvider? = null

    fun init(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                applyRegion()
                val provider = WebPoTokenProvider(app.applicationContext)
                poProvider = provider
                YoutubeStreamExtractor.setPoTokenProvider(provider)
                ready.value = true
                status.value = "Engine ready (NewPipe extractor)"
            } catch (e: Throwable) {
                status.value = "Engine failed to start: ${e.message}"
            }
        }
    }

    /** Tells YouTube which country to rank results for (this phone's, or the one chosen in Settings). */
    fun applyRegion() {
        val device = java.util.Locale.getDefault().country
        val code = if (Prefs.searchRegion == "auto") device.ifBlank { "US" } else Prefs.searchRegion
        NewPipe.init(NpDownloader(), Localization.DEFAULT, ContentCountry(code))
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

    private fun runSearch(svc: StreamingService, q: String, filters: List<String>): Pair<SearchInfo, SearchQueryHandler> {
        val handler = svc.searchQHFactory.fromQuery(q, filters, "")
        return SearchInfo.getInfo(svc, handler) to handler
    }

    private fun videosOf(info: SearchInfo): List<StreamInfoItem> = info.relatedItems.filterIsInstance<StreamInfoItem>()

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        if (!ready.value) throw IllegalStateException(status.value)
        val q = query.trim().replace(Regex("\\s+"), " ")
        val svc = ServiceList.YouTube
        if (q.startsWith("http://") || q.startsWith("https://")) {
            when (svc.getLinkTypeByUrl(q)) {
                StreamingService.LinkType.STREAM -> {
                    val info = StreamInfo.getInfo(q)
                    StreamCache.put(info.url, info)
                    StreamCache.put(q, info)
                    SearchPage(listOf(info.toItem()))
                }
                StreamingService.LinkType.PLAYLIST -> {
                    val pl = PlaylistInfo.getInfo(svc, q)
                    SearchPage(pl.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toItem() })
                }
                else -> throw IllegalStateException("Paste a video or playlist link")
            }
        } else {
            // 1. the same search youtube.com does (all result types, we keep the videos)
            var (info, handler) = runSearch(svc, q, emptyList())
            var items = videosOf(info)
            if (items.isEmpty()) {
                val r = runSearch(svc, q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS))
                info = r.first
                handler = r.second
                items = videosOf(info)
            }
            // 2. what YouTube says about the spelling
            val suggestion = info.searchSuggestion?.takeIf { it.isNotBlank() }
            var corrected: String? = if (info.isCorrectedSearch) suggestion else null
            var didYouMean: String? = if (!info.isCorrectedSearch) suggestion else null
            // 3. too few results: try YouTube's own spelling hint, then its top autocomplete
            if (Prefs.autoCorrect && items.size < 4) {
                val alt = didYouMean ?: runCatching { suggest(q).firstOrNull { !it.equals(q, ignoreCase = true) } }.getOrNull()
                if (!alt.isNullOrBlank()) {
                    val r = runCatching { runSearch(svc, alt, emptyList()) }.getOrNull()
                    val better = r?.let { videosOf(it.first) }.orEmpty()
                    if (r != null && better.size > items.size) {
                        info = r.first
                        handler = r.second
                        items = better
                        corrected = alt
                        didYouMean = null
                    }
                }
            }
            SearchPage(items.map { it.toItem() }, corrected, didYouMean, info.nextPage?.let { MoreToken(handler, it) })
        }
    }

    /** The next page of the same search. */
    suspend fun searchMore(token: MoreToken): SearchPage = withContext(Dispatchers.IO) {
        val page = SearchInfo.getMoreItems(ServiceList.YouTube, token.handler, token.page)
        val items = page.items.filterIsInstance<StreamInfoItem>().map { it.toItem() }
        SearchPage(items, more = page.nextPage?.let { MoreToken(token.handler, it) })
    }

    /** Live YouTube search suggestions (the same ones youtube.com shows while you type). */
    fun suggest(query: String): List<String> {
        if (!ready.value) return emptyList()
        return ServiceList.YouTube.suggestionExtractor.suggestionList(query).take(10)
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
        for (round in 1..3) {
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
            Thread.sleep(700L * round)
        }
        return null
    }

    private class Progress(val onProgress: (Float, Long, Long, Long) -> Unit) {
        val done = AtomicLong(0)
        @Volatile var total = 1L
        private val started = System.currentTimeMillis()
        @Volatile private var lastEmit = 0L

        fun add(n: Long) {
            val d = done.addAndGet(n).coerceAtLeast(0)
            val now = System.currentTimeMillis()
            if (now - lastEmit < 120 && d < total) return
            lastEmit = now
            val frac = (d.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.92f
            val secs = (now - started) / 1000.0
            val eta = if (secs > 2 && d > 0) ((total - d) / (d / secs)).toLong().coerceAtLeast(0) else 0L
            onProgress(frac, eta, d, total)
        }
    }

    private fun fetchFile(url: String, expected: Long, dest: File, threads: Int, token: CancelToken, p: Progress): Long {
        val probed = runCatching { ChunkDownloader.probeLength(url) }
        val len = probed.getOrNull()
            ?: if (expected > 0) expected else throw (probed.exceptionOrNull() ?: IOException("Could not read the file size"))
        if (expected > 100_000 && len < expected / 2) {
            throw BadMediaException("The server sent a tiny placeholder (${fmtSize(len)}) instead of the real stream (${fmtSize(expected)})")
        }
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

    private fun headHex(f: File): String = try {
        f.inputStream().use { s ->
            val b = ByteArray(12)
            val n = s.read(b)
            if (n <= 0) "empty file" else b.take(n).joinToString(" ") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        "unreadable"
    }

    /** Joins the streams (Android's muxer first, our own re-wrapper if that fails) and refuses broken results. */
    private fun muxChecked(
        video: File?, audio: File?, out: File, webm: Boolean,
        token: CancelToken, minBytes: Long, log: StringBuilder,
    ) {
        var firstError: Exception? = null
        try {
            Muxer.mux(video, audio, out, webm, token)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            firstError = e
            log.append("  Android muxer: ").append(e.message).append('\n')
        }
        val first = firstError
        if (first != null) {
            val src = video ?: audio!!
            if (webm) {
                throw BadMediaException("Could not read the downloaded media (${first.message}). Source ${src.length()} bytes, starts with ${headHex(src)}")
            }
            out.delete()
            try {
                val n = Fmp4.remux(video, audio, out, token)
                Muxer.verify(out)
                log.append("  Own re-wrapper OK: ").append(n).append(" samples\n")
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BadMediaException(
                    "Could not read the downloaded media (${first.message}; own re-wrapper: ${e.message}). " +
                        "Source ${src.length()} bytes, starts with ${headHex(src)}, boxes: ${Fmp4.summary(src)}"
                )
            }
        }
        val minOk = maxOf(20_000L, minBytes)
        if (out.length() < minOk) {
            throw BadMediaException("The result is only ${out.length()} bytes (expected at least ${fmtSize(minOk)}), so it was not saved")
        }
    }

    private fun describe(s: org.schabi.newpipe.extractor.stream.Stream): String {
        val host = s.content.toHttpUrlOrNull()?.host ?: "?"
        val itag = s.content.toHttpUrlOrNull()?.queryParameter("itag") ?: "?"
        return "client=${StreamUa.clientOf(s.content)} itag=$itag ${s.format?.suffix ?: "?"} " +
            "expected=${streamBytesOf(s)}B host=$host"
    }

    /** Blocking. Returns a message for the queue. Throws on failure or cancel. Up to 3 attempts, each different. */
    fun download(
        ctx: Context,
        t: DownloadTask,
        token: CancelToken,
        onProgress: (Float, Long, Long, Long) -> Unit,
    ): String {
        if (Prefs.wifiOnly) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            if (cm != null && cm.isActiveNetworkMetered) {
                throw IllegalStateException("Wi-Fi only is on, but you're on mobile data")
            }
        }
        val log = StringBuilder()
        log.append("YTGrab ").append(APP_VERSION).append(" | Android ").append(Build.VERSION.RELEASE)
            .append(" | ").append(Build.MODEL).append('\n')
        log.append("Video: ").append(t.item.url).append('\n')
        log.append("Wanted: ").append(t.options.mode).append(' ')
            .append(if (t.options.mode == Mode.VIDEO) "${t.options.videoHeight}p" else "${t.options.audioBitrate}")
            .append(" | threads ").append(t.options.threads).append('\n')
        var last: IOException? = null
        for (attempt in 1..3) {
            try {
                return downloadOnce(ctx, t, token, onProgress, attempt, log)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: IOException) {
                last = e
                log.append("Attempt ").append(attempt).append(" failed: ").append(e.message).append('\n')
            }
            token.check()
            if (attempt < 3) {
                // links or the proof-of-origin session may have gone stale: start over with fresh ones
                StreamCache.remove(t.item.url)
                poProvider?.invalidate()
                onProgress(0f, 0L, 0L, 0L)
            }
        }
        throw DownloadFailed(last?.message ?: "Download failed", log.toString())
    }

    private fun downloadOnce(
        ctx: Context,
        t: DownloadTask,
        token: CancelToken,
        onProgress: (Float, Long, Long, Long) -> Unit,
        attempt: Int,
        log: StringBuilder,
    ): String {
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

            var fallbackNote = ""
            val muxedFallback = if (attempt >= 3) opts.video.lastOrNull { it.audio == null } else null
            log.append("Attempt ").append(attempt).append(": ")

            if (o.mode == Mode.AUDIO) {
                val a = pickAudio(opts, o.audioBitrate) ?: throw IllegalStateException("No audio stream found")
                outFile = File(tmp, fileNameFor(info, safe, "${a.kbps}k") + ".m4a")
                if (muxedFallback != null) {
                    // last resort: take the sound out of a combined (video + sound) stream
                    log.append("sound from the ").append(muxedFallback.height).append("p combined stream | ")
                        .append(describe(muxedFallback.video)).append('\n')
                    val raw = File(tmp, "combined.src")
                    progress.total = muxedFallback.bytes.coerceAtLeast(1)
                    fetchFile(muxedFallback.video.content, muxedFallback.bytes, raw, o.threads, token, progress)
                    token.check()
                    muxChecked(null, raw, outFile, false, token, 50_000L, log)
                    fallbackNote = " (sound taken from a combined stream)"
                } else {
                    val stream = if (attempt == 2) (a.alts.firstOrNull() ?: a.stream) else a.stream
                    log.append(describe(stream)).append('\n')
                    val raw = File(tmp, "audio.src")
                    progress.total = streamBytesOf(stream).coerceAtLeast(1)
                    val len = fetchFile(stream.content, streamBytesOf(stream), raw, o.threads, token, progress)
                    token.check()
                    muxChecked(null, raw, outFile, false, token, len / 2, log)
                }
            } else {
                val v0 = pickVideo(opts, o.videoHeight) ?: throw IllegalStateException("No video stream found")
                val v = muxedFallback ?: v0
                if (muxedFallback != null) fallbackNote = " (fell back to ${v.height}p with sound)"
                isWebm = v.webm
                val ext = if (v.webm) "webm" else "mp4"
                outFile = File(tmp, fileNameFor(info, safe, "${v.height}p") + "." + ext)
                if (v.audio == null) {
                    log.append(describe(v.video)).append('\n')
                    progress.total = v.bytes.coerceAtLeast(1)
                    fetchFile(v.video.content, v.bytes, outFile, o.threads, token, progress)
                } else {
                    val aStream = if (attempt == 2) (v.audioAlts.firstOrNull() ?: v.audio) else v.audio
                    log.append(describe(v.video)).append(" + ").append(describe(aStream)).append('\n')
                    val vf = File(tmp, "video.src")
                    val af = File(tmp, "audio.src")
                    progress.total = v.bytes.coerceAtLeast(1)
                    val vl = fetchFile(v.video.content, streamBytesOf(v.video), vf, o.threads, token, progress)
                    val al = fetchFile(aStream.content, streamBytesOf(aStream), af, o.threads, token, progress)
                    progress.total = (vl + al).coerceAtLeast(1)
                    token.check()
                    muxChecked(vf, af, outFile, v.webm, token, (vl + al) / 2, log)
                }
            }
            onProgress(0.95f, 0L, progress.done.get(), progress.total)
            token.check()

            // artwork + tags (each can be switched off in Settings)
            val wantArt = if (o.mode == Mode.AUDIO) Prefs.artAudio else Prefs.artVideo
            val wantTags = Prefs.embedTags
            var note = ""
            if (!isWebm && (outFile.extension == "mp4" || outFile.extension == "m4a") && (wantArt || wantTags)) {
                val cover = if (wantArt) fetchCover(info, o.mode == Mode.AUDIO && Prefs.squareCover, Prefs.coverQuality) else null
                val artist = (info.uploaderName ?: "").removeSuffix(" - Topic")
                val reason = Mp4Tagger.tag(
                    outFile,
                    if (wantTags) (info.name ?: safe) else "",
                    if (wantTags) artist else "",
                    cover,
                )
                note = when {
                    !wantArt -> ""
                    cover == null -> " (the cover could not be downloaded)"
                    reason != null -> " (artwork not embedded: $reason)"
                    else -> " (with artwork)"
                }
            } else if (isWebm && wantArt) {
                note = " (WebM can't hold artwork here)"
            }
            token.check()
            saveToDownloads(ctx, outFile, folder)
            return "Saved to Downloads/$folder$note$fallbackNote"
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
