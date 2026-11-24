package com.ytgrab.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Prefs {
    private var sp: SharedPreferences? = null

    var mode by mutableStateOf(Mode.VIDEO)
    var videoHeight by mutableIntStateOf(1080)
    var audioBitrate by mutableStateOf("Best")
    var threads by mutableIntStateOf(4)
    var squareCover by mutableStateOf(true)
    var maxParallel by mutableIntStateOf(2)

    fun load(c: Context) {
        val s = c.getSharedPreferences("ytgrab2", Context.MODE_PRIVATE)
        sp = s
        mode = runCatching { Mode.valueOf(s.getString("mode", "VIDEO")!!) }.getOrDefault(Mode.VIDEO)
        videoHeight = s.getInt("videoHeight", 1080)
        audioBitrate = s.getString("audioBitrate", "Best") ?: "Best"
        threads = s.getInt("threads", 4)
        squareCover = s.getBoolean("squareCover", true)
        maxParallel = s.getInt("maxParallel", 2)
    }

    fun save() {
        sp?.edit()
            ?.putString("mode", mode.name)
            ?.putInt("videoHeight", videoHeight)
            ?.putString("audioBitrate", audioBitrate)
            ?.putInt("threads", threads)
            ?.putBoolean("squareCover", squareCover)
            ?.putInt("maxParallel", maxParallel)
            ?.apply()
    }

    fun options() = DownloadOptions(mode, videoHeight, audioBitrate, threads, squareCover)
}
