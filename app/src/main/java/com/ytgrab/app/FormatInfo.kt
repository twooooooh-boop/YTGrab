package com.ytgrab.app

import java.util.concurrent.ConcurrentHashMap

data class FormatInfo(
    val durationSec: Long,
    val videoSizes: List<Pair<Int, Long>>, // (height, total bytes incl. audio), tallest first
    val audioBest: Long,
    val audioM4a: Long,
    val audioOpus: Long,
)

object FormatCache {
    private val map = ConcurrentHashMap<String, FormatInfo>()
    fun get(url: String): FormatInfo? = map[url]
    fun put(url: String, i: FormatInfo) { map[url] = i }
}

fun FormatInfo.videoSize(height: Int): Long {
    if (videoSizes.isEmpty()) return 0L
    if (height == 0) return videoSizes.first().second
    return (videoSizes.firstOrNull { it.first <= height } ?: videoSizes.last()).second
}

fun audioSize(i: FormatInfo, fmt: String, br: String): Long {
    val d = i.durationSec
    val kbps = br.removeSuffix("K").toIntOrNull()
    return when {
        kbps != null -> if (d > 0) d * kbps * 125L else 0L
        fmt == "m4a" -> if (i.audioM4a > 0) i.audioM4a else i.audioBest
        fmt == "opus" -> if (i.audioOpus > 0) i.audioOpus else i.audioBest
        else -> if (d > 0) d * 245 * 125L else i.audioBest
    }
}

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_000_000_000L -> "%.2f GB".format(b / 1_000_000_000.0)
    else -> "%.1f MB".format(b / 1_000_000.0)
}
