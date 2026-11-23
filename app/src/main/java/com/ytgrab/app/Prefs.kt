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
    var audioFormat by mutableStateOf("m4a")
    var audioBitrate by mutableStateOf("Best")
    var threads by mutableIntStateOf(4)
    var useAria2c by mutableStateOf(false)
    var squareCover by mutableStateOf(true)
    var maxParallel by mutableIntStateOf(2)
    var ytClient by mutableStateOf("auto")

    fun load(c: Context) {
        val s = c.getSharedPreferences("ytgrab", Context.MODE_PRIVATE)
        sp = s
        mode = runCatching { Mode.valueOf(s.getString("mode", "VIDEO")!!) }.getOrDefault(Mode.VIDEO)
        videoHeight = s.getInt("videoHeight", 1080)
        audioFormat = s.getString("audioFormat", "m4a") ?: "m4a"
        audioBitrate = s.getString("audioBitrate", "Best") ?: "Best"
        threads = s.getInt("threads", 4)
        useAria2c = s.getBoolean("useAria2c", false)
        squareCover = s.getBoolean("squareCover", true)
        maxParallel = s.getInt("maxParallel", 2)
        ytClient = s.getString("ytClient", "auto") ?: "auto"
    }

    fun save() {
        sp?.edit()
            ?.putString("mode", mode.name)
            ?.putInt("videoHeight", videoHeight)
            ?.putString("audioFormat", audioFormat)
            ?.putString("audioBitrate", audioBitrate)
            ?.putInt("threads", threads)
            ?.putBoolean("useAria2c", useAria2c)
            ?.putBoolean("squareCover", squareCover)
            ?.putInt("maxParallel", maxParallel)
            ?.putString("ytClient", ytClient)
            ?.apply()
    }

    fun options() = DownloadOptions(mode, videoHeight, audioFormat, audioBitrate, threads, useAria2c, squareCover)
}
