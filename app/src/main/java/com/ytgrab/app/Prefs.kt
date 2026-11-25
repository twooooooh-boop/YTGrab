package com.ytgrab.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Every setting lives here, is saved as one JSON text, and the same JSON is used for backup / restore. */
object Prefs {
    private var sp: SharedPreferences? = null

    // appearance
    var themeId by mutableStateOf("dynamic")      // "dynamic" or a palette id
    var appearance by mutableStateOf("system")    // system / light / dark
    var amoled by mutableStateOf(false)
    var showThumbs by mutableStateOf(true)

    // download defaults
    var mode by mutableStateOf(Mode.VIDEO)
    var videoHeight by mutableIntStateOf(1080)    // 0 = best
    var audioBitrate by mutableStateOf("Best")
    var fallbackBest by mutableStateOf(true)      // missing quality -> best (false = closest lower)
    var threads by mutableIntStateOf(4)
    var maxParallel by mutableIntStateOf(2)
    var chunkMb by mutableIntStateOf(4)
    var wifiOnly by mutableStateOf(false)

    // artwork & tags
    var artVideo by mutableStateOf(true)
    var artAudio by mutableStateOf(true)
    var squareCover by mutableStateOf(true)
    var coverQuality by mutableIntStateOf(92)
    var embedTags by mutableStateOf(true)

    // files
    var folder by mutableStateOf("YTGrab")
    var nameTemplate by mutableStateOf("title")   // title / artist_title / title_quality

    // behaviour
    var closeAfterAdd by mutableStateOf(true)
    var openQueueAfterAdd by mutableStateOf(false)
    var saveHistory by mutableStateOf(true)
    var history by mutableStateOf<List<String>>(emptyList())

    fun load(c: Context) {
        val s = c.getSharedPreferences("ytgrab3", Context.MODE_PRIVATE)
        sp = s
        s.getString("all", null)?.let { runCatching { fromJson(JSONObject(it)) } }
    }

    fun save() {
        sp?.edit()?.putString("all", toJson().toString())?.apply()
    }

    fun options() = DownloadOptions(mode, videoHeight, audioBitrate, threads)

    fun toJson(): JSONObject = JSONObject().apply {
        put("app", "YTGrab")
        put("format", 1)
        put("themeId", themeId)
        put("appearance", appearance)
        put("amoled", amoled)
        put("showThumbs", showThumbs)
        put("mode", mode.name)
        put("videoHeight", videoHeight)
        put("audioBitrate", audioBitrate)
        put("fallbackBest", fallbackBest)
        put("threads", threads)
        put("maxParallel", maxParallel)
        put("chunkMb", chunkMb)
        put("wifiOnly", wifiOnly)
        put("artVideo", artVideo)
        put("artAudio", artAudio)
        put("squareCover", squareCover)
        put("coverQuality", coverQuality)
        put("embedTags", embedTags)
        put("folder", folder)
        put("nameTemplate", nameTemplate)
        put("closeAfterAdd", closeAfterAdd)
        put("openQueueAfterAdd", openQueueAfterAdd)
        put("saveHistory", saveHistory)
        put("history", JSONArray(history))
    }

    /** Returns false when the JSON is not a YTGrab backup. Bad values are ignored, not trusted. */
    fun fromJson(o: JSONObject): Boolean {
        if (o.optString("app") != "YTGrab") return false
        val id = o.optString("themeId", themeId)
        if (id == "dynamic" || PALETTES.any { it.id == id }) themeId = id
        val ap = o.optString("appearance", appearance)
        if (ap in listOf("system", "light", "dark")) appearance = ap
        amoled = o.optBoolean("amoled", amoled)
        showThumbs = o.optBoolean("showThumbs", showThumbs)
        runCatching { Mode.valueOf(o.optString("mode", mode.name)) }.getOrNull()?.let { mode = it }
        videoHeight = o.optInt("videoHeight", videoHeight).takeIf { it == 0 || it in 100..4320 } ?: videoHeight
        val br = o.optString("audioBitrate", audioBitrate)
        if (br == "Best" || br.toIntOrNull() != null) audioBitrate = br
        fallbackBest = o.optBoolean("fallbackBest", fallbackBest)
        threads = o.optInt("threads", threads).coerceIn(1, 16)
        maxParallel = o.optInt("maxParallel", maxParallel).coerceIn(1, 5)
        chunkMb = o.optInt("chunkMb", chunkMb).takeIf { it in listOf(2, 4, 8) } ?: chunkMb
        wifiOnly = o.optBoolean("wifiOnly", wifiOnly)
        artVideo = o.optBoolean("artVideo", artVideo)
        artAudio = o.optBoolean("artAudio", artAudio)
        squareCover = o.optBoolean("squareCover", squareCover)
        coverQuality = o.optInt("coverQuality", coverQuality).coerceIn(50, 100)
        embedTags = o.optBoolean("embedTags", embedTags)
        folder = cleanFolder(o.optString("folder", folder)).ifBlank { "YTGrab" }
        val nt = o.optString("nameTemplate", nameTemplate)
        if (nt in listOf("title", "artist_title", "title_quality")) nameTemplate = nt
        closeAfterAdd = o.optBoolean("closeAfterAdd", closeAfterAdd)
        openQueueAfterAdd = o.optBoolean("openQueueAfterAdd", openQueueAfterAdd)
        saveHistory = o.optBoolean("saveHistory", saveHistory)
        o.optJSONArray("history")?.let { a ->
            history = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(15)
        }
        return true
    }

    fun reset() {
        themeId = "dynamic"; appearance = "system"; amoled = false; showThumbs = true
        mode = Mode.VIDEO; videoHeight = 1080; audioBitrate = "Best"; fallbackBest = true
        threads = 4; maxParallel = 2; chunkMb = 4; wifiOnly = false
        artVideo = true; artAudio = true; squareCover = true; coverQuality = 92; embedTags = true
        folder = "YTGrab"; nameTemplate = "title"
        closeAfterAdd = true; openQueueAfterAdd = false; saveHistory = true
        history = emptyList()
        save()
    }

    fun cleanFolder(s: String): String =
        s.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }.take(40)

    fun addHistory(q: String) {
        if (!saveHistory || q.isBlank()) return
        history = (listOf(q.trim()) + history.filterNot { it.equals(q.trim(), ignoreCase = true) }).take(15)
        save()
    }

    fun removeHistory(q: String) {
        history = history.filterNot { it == q }
        save()
    }

    fun clearHistory() {
        history = emptyList()
        save()
    }
}
