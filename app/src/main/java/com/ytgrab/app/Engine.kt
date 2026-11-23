package com.ytgrab.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Thin wrapper around yt-dlp (youtubedl-android): init, search, download, update. */
object Engine {
    val status = MutableStateFlow("Starting engine…")
    val ready = MutableStateFlow(false)

    private val MEDIA_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "flac", "mov", "aac", "wav")

    fun init(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                runCatching { Aria2c.getInstance().init(app) }
                ready.value = true
                status.value = "Engine ready"
            } catch (e: Throwable) {
                status.value = "Engine failed to start: ${e.message}"
            }
        }
    }

    suspend fun updateYtDlp(app: Context): String = withContext(Dispatchers.IO) {
        try {
            val r = YoutubeDL.getInstance().updateYoutubeDL(app)
            "yt-dlp update: $r"
        } catch (e: Throwable) {
            "Update failed: ${e.message}"
        }
    }

    // ---------- search ----------

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    suspend fun search(query: String, count: Int = 15): List<SearchItem> = withContext(Dispatchers.IO) {
        if (!ready.value) throw IllegalStateException(status.value)
        val q = query.trim()
        val isUrl = q.startsWith("http://") || q.startsWith("https://")
        val req = YoutubeDLRequest(if (isUrl) q else "ytsearch$count:$q")
        req.addOption("--flat-playlist")
        req.addOption("--dump-json")
        req.addOption("--no-warnings")
        req.addOption("--playlist-end", "100")
        val res = YoutubeDL.getInstance().execute(req, "search-" + System.nanoTime()) { _, _, _ -> }
        res.out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { parse(it) }
            .toList()
    }

    private fun parse(line: String): SearchItem? {
        return try {
            val o = JSONObject(line)
            val id = o.str("id")
            var url = o.str("webpage_url").ifBlank { o.str("url") }
            if (!url.startsWith("http") && id.isNotBlank()) url = "https://www.youtube.com/watch?v=$id"
            if (url.isBlank()) return null
            var thumb = o.str("thumbnail")
            if (thumb.isBlank()) {
                val arr = o.optJSONArray("thumbnails")
                if (arr != null && arr.length() > 0) thumb = arr.getJSONObject(arr.length() - 1).optString("url")
            }
            if (thumb.isBlank() && id.length == 11) thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            val dur = if (o.isNull("duration")) 0L else o.optDouble("duration", 0.0).toLong()
            SearchItem(
                id = id.ifBlank { url },
                title = o.str("title").ifBlank { "(untitled)" },
                channel = o.str("channel").ifBlank { o.str("uploader") },
                durationSec = dur,
                url = url,
                thumb = thumb,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun formats(url: String): FormatInfo = withContext(Dispatchers.IO) {
        if (!ready.value) throw IllegalStateException(status.value)
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-json")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val res = YoutubeDL.getInstance().execute(req, "info-" + System.nanoTime()) { _, _, _ -> }
        val line = res.out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }
            ?: throw IllegalStateException("No format info")
        val o = JSONObject(line)
        val dur = if (o.isNull("duration")) 0.0 else o.optDouble("duration", 0.0)
        val arr = o.optJSONArray("formats") ?: throw IllegalStateException("No formats")

        fun num(f: JSONObject, k: String): Double = if (f.isNull(k)) 0.0 else f.optDouble(k, 0.0)
        fun size(f: JSONObject): Long {
            val fs = num(f, "filesize")
            if (fs > 0) return fs.toLong()
            val fa = num(f, "filesize_approx")
            if (fa > 0) return fa.toLong()
            val tbr = num(f, "tbr")
            return if (tbr > 0 && dur > 0) (tbr * 125 * dur).toLong() else 0L
        }

        val anyV = HashMap<Int, Long>()
        val avc = HashMap<Int, Long>()
        val muxed = HashMap<Int, Long>()
        var aBest = 0L
        var aM4a = 0L
        var aOpus = 0L
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            if (f.str("ext") == "mhtml") continue
            val vc = f.str("vcodec")
            val ac = f.str("acodec")
            val hasV = vc.isNotBlank() && vc != "none"
            val hasA = ac.isNotBlank() && ac != "none"
            val h = f.optInt("height", 0)
            val s = size(f)
            if (hasV && !hasA) {
                if (h > 0) {
                    anyV[h] = maxOf(anyV[h] ?: 0L, s)
                    if (vc.startsWith("avc1")) avc[h] = maxOf(avc[h] ?: 0L, s)
                }
            } else if (!hasV && hasA) {
                aBest = maxOf(aBest, s)
                if (f.str("ext") == "m4a") aM4a = maxOf(aM4a, s)
                if (ac.startsWith("opus")) aOpus = maxOf(aOpus, s)
            } else if (hasV && hasA && h > 0) {
                muxed[h] = maxOf(muxed[h] ?: 0L, s)
            }
        }
        val audioForMux = if (aM4a > 0) aM4a else aBest
        val heights = (anyV.keys + muxed.keys).toSet().sortedDescending()
        val sizes = heights.map { h ->
            val v = avc[h] ?: anyV[h]
            val total = if (v == null) (muxed[h] ?: 0L) else if (v > 0) v + audioForMux else 0L
            h to total
        }
        FormatInfo(dur.toLong(), sizes, aBest, aM4a, aOpus)
    }

    // ---------- download ----------

    private fun buildRequest(t: DownloadTask, tmp: File, embedArt: Boolean): YoutubeDLRequest {
        val o = t.options
        val r = YoutubeDLRequest(t.item.url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--retries", "10")
        r.addOption("--fragment-retries", "10")
        r.addOption("-o", tmp.absolutePath + "/%(title).120B.%(ext)s")

        // multi-threading
        val n = o.threads.coerceIn(1, 16)
        if (o.useAria2c) {
            r.addOption("--downloader", "libaria2c.so")
            r.addOption("--external-downloader-args", "aria2c:-x $n -s $n -k 1M --summary-interval=1")
        } else {
            r.addOption("-N", n.toString())
        }

        if (o.mode == Mode.VIDEO) {
            val h = o.videoHeight
            val f = if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
            r.addOption("-f", f)
            r.addOption("-S", "res,vcodec:h264,acodec:m4a")
            r.addOption("--merge-output-format", "mp4/mkv")
        } else {
            r.addOption("-f", "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", o.audioFormat)
            r.addOption("--audio-quality", if (o.audioBitrate == "Best") "0" else o.audioBitrate)
        }

        r.addOption("--embed-metadata")
        if (embedArt) {
            r.addOption("--embed-thumbnail")
            if (o.mode == Mode.AUDIO && o.squareCover) {
                r.addOption("--convert-thumbnails", "png")
                r.addOption(
                    "--ppa",
                    "ThumbnailsConvertor+ffmpeg_o:-c:v png -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""
                )
            } else {
                r.addOption("--convert-thumbnails", "jpg")
            }
        }
        return r
    }

    private fun exec(r: YoutubeDLRequest, id: String, onProgress: (Float, Long) -> Unit) {
        YoutubeDL.getInstance().execute(r, id) { p, eta, _ -> onProgress(p, eta) }
    }

    /** Blocking. Returns a human message. Throws on failure/cancel. */
    fun download(
        ctx: Context,
        t: DownloadTask,
        isCancelled: () -> Boolean,
        onProgress: (Float, Long) -> Unit,
    ): String {
        val tmp = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "tmp/${t.id}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var artworkOk = true
        try {
            try {
                exec(buildRequest(t, tmp, true), t.id, onProgress)
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                val artFail = m.contains("thumbnail", true) || m.contains("mutagen", true)
                if (isCancelled() || !artFail) throw e
                artworkOk = false // keep the media, retry without artwork
                exec(buildRequest(t, tmp, false), t.id, onProgress)
            }
            val files = tmp.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }
                .orEmpty()
            if (files.isEmpty()) error("No output file was produced")
            files.forEach { saveToDownloads(ctx, it) }
            return if (artworkOk) "Saved to Downloads/YTGrab"
            else "Saved to Downloads/YTGrab (artwork could not be embedded)"
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun saveToDownloads(ctx: Context, f: File) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(f.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YTGrab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
