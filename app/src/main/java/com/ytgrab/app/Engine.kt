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
