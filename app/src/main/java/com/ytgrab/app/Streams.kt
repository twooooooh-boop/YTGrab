package com.ytgrab.app

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.ConcurrentHashMap

class VideoOpt(
    val height: Int,
    val video: VideoStream,
    val audio: AudioStream?,   // null = the video stream already contains sound
    val bytes: Long,
    val webm: Boolean,
)

class AudioOpt(val kbps: Int, val stream: AudioStream, val bytes: Long)

class StreamOptions(val info: StreamInfo, val video: List<VideoOpt>, val audio: List<AudioOpt>)

object StreamCache {
    private val map = ConcurrentHashMap<String, StreamInfo>()
    fun get(url: String): StreamInfo? = map[url]
    fun put(url: String, info: StreamInfo) { map[url] = info }
    fun clear() { map.clear() }
}

private fun streamBytes(s: Stream?): Long {
    val n = s?.itagItem?.contentLength ?: return 0L
    return if (n > 0) n else 0L
}

private fun suffixOf(s: Stream): String = s.format?.suffix ?: ""

fun buildOptions(info: StreamInfo): StreamOptions {
    val http = DeliveryMethod.PROGRESSIVE_HTTP
    val auds = info.audioStreams.filter { it.deliveryMethod == http && it.isUrl }
    val m4a = auds.filter { suffixOf(it) == "m4a" }.sortedByDescending { it.averageBitrate }
    val webmA = auds.filter { suffixOf(it) == "webm" }.sortedByDescending { it.averageBitrate }
    val bestM4a = m4a.firstOrNull()
    val bestWebmA = webmA.firstOrNull()

    val audioOpts = m4a
        .distinctBy { it.averageBitrate }
        .map { AudioOpt(it.averageBitrate, it, streamBytes(it)) }

    val videoOpts = ArrayList<VideoOpt>()
    val vOnly = info.videoOnlyStreams.filter { it.deliveryMethod == http && it.isUrl && it.height > 0 }
    for ((h, list) in vOnly.groupBy { it.height }.entries.sortedByDescending { it.key }) {
        val avc = list.filter { it.codec?.startsWith("avc1") == true }.maxByOrNull { it.bitrate }
        val vp9 = list.filter { suffixOf(it) == "webm" && it.codec?.startsWith("vp9") == true }
            .maxByOrNull { it.bitrate }
        if (avc != null && bestM4a != null) {
            videoOpts += VideoOpt(h, avc, bestM4a, streamBytes(avc) + streamBytes(bestM4a), false)
        } else if (vp9 != null && bestWebmA != null) {
            videoOpts += VideoOpt(h, vp9, bestWebmA, streamBytes(vp9) + streamBytes(bestWebmA), true)
        }
    }
    // streams that already include sound (usually 360p) for heights we could not build otherwise
    for (s in info.videoStreams.filter { it.deliveryMethod == http && it.isUrl && it.height > 0 }) {
        if (videoOpts.none { it.height == s.height }) {
            videoOpts += VideoOpt(s.height, s, null, streamBytes(s), suffixOf(s) == "webm")
        }
    }
    videoOpts.sortByDescending { it.height }
    return StreamOptions(info, videoOpts, audioOpts)
}

/** Exact quality if it exists. Otherwise Best (default) or the closest lower one. Never fails when any stream exists. */
fun pickVideo(o: StreamOptions, height: Int, fallbackBest: Boolean = Prefs.fallbackBest): VideoOpt? {
    if (o.video.isEmpty()) return null
    if (height == 0) return o.video.first()
    o.video.firstOrNull { it.height == height }?.let { return it }
    return if (fallbackBest) o.video.first() else (o.video.firstOrNull { it.height <= height } ?: o.video.first())
}

fun pickAudio(o: StreamOptions, bitrate: String, fallbackBest: Boolean = Prefs.fallbackBest): AudioOpt? {
    if (o.audio.isEmpty()) return null
    val target = bitrate.toIntOrNull() ?: return o.audio.first()
    o.audio.firstOrNull { it.kbps == target }?.let { return it }
    return if (fallbackBest) o.audio.first() else (o.audio.firstOrNull { it.kbps <= target } ?: o.audio.first())
}

fun videoChoices(o: StreamOptions?): List<QOpt> {
    if (o == null || o.video.isEmpty()) return emptyList()
    val best = o.video.first()
    val list = ArrayList<QOpt>()
    list += QOpt("0", "Best", "${best.height}p · " + sizeText(best.bytes))
    for (v in o.video) {
        list += QOpt(v.height.toString(), "${v.height}p" + (if (v.webm) " · WebM (no artwork)" else ""), sizeText(v.bytes))
    }
    return list
}

fun audioChoices(o: StreamOptions?): List<QOpt> {
    if (o == null || o.audio.isEmpty()) return emptyList()
    val best = o.audio.first()
    val list = ArrayList<QOpt>()
    list += QOpt("Best", "Best", "${best.kbps} kbps · " + sizeText(best.bytes))
    for (a in o.audio) list += QOpt(a.kbps.toString(), "${a.kbps} kbps · m4a", sizeText(a.bytes))
    return list
}

private fun sizeText(b: Long) = if (b > 0) fmtSize(b) else "size unknown"
