package com.ytgrab.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    val audioAlts: List<AudioStream> = emptyList(),   // other audio streams to try if the first one fails
)

class AudioOpt(val kbps: Int, val stream: AudioStream, val bytes: Long, val alts: List<AudioStream> = emptyList())

class StreamOptions(val info: StreamInfo, val video: List<VideoOpt>, val audio: List<AudioOpt>)

object StreamCache {
    private class Entry(val info: StreamInfo, val at: Long)
    private const val TTL_MS = 10 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    /** Stream links expire, so cached info is only trusted for a few minutes. */
    fun get(url: String): StreamInfo? {
        val e = map[url] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            map.remove(url)
            return null
        }
        return e.info
    }

    fun put(url: String, info: StreamInfo) { map[url] = Entry(info, System.currentTimeMillis()) }
    fun remove(url: String) { map.remove(url) }
    fun clear() { map.clear() }
}

private fun streamBytes(s: Stream?): Long {
    val n = s?.itagItem?.contentLength ?: return 0L
    return if (n > 0) n else 0L
}

private fun suffixOf(s: Stream): String = s.format?.suffix ?: ""

private fun itagOf(s: Stream): String = s.content.toHttpUrlOrNull()?.queryParameter("itag") ?: ""

/** Dubbed, audio-description and "loud" (DRC) tracks are not what anyone means by "the audio". */
private fun isPlainTrack(s: Stream): Boolean {
    val u = try { java.net.URLDecoder.decode(s.content, "UTF-8") } catch (e: Exception) { s.content }
    return !(u.contains("acont=dubbed") || u.contains("acont=descriptive") ||
        u.contains("acont=secondary") || u.contains("drc=1"))
}

/** Normal stereo AAC first (itag 140 / 141 / 139), surround and odd tracks only as spare options. */
private val STEREO_AAC = setOf("140", "141", "139")

fun buildOptions(info: StreamInfo): StreamOptions {
    val http = DeliveryMethod.PROGRESSIVE_HTTP
    val auds = info.audioStreams.filter { it.deliveryMethod == http && it.isUrl }
    val m4aAll = auds.filter { suffixOf(it) == "m4a" }
    val m4aPlain = m4aAll.filter { isPlainTrack(it) }.ifEmpty { m4aAll }
    val m4aStd = m4aPlain.filter { itagOf(it) in STEREO_AAC }.ifEmpty { m4aPlain }
        .sortedByDescending { it.averageBitrate }
    val m4aRest = m4aPlain.filter { x -> m4aStd.none { it === x } }.sortedByDescending { it.averageBitrate }
    val m4aOrder = m4aStd + m4aRest
    val webmA = auds.filter { suffixOf(it) == "webm" && isPlainTrack(it) }.sortedByDescending { it.averageBitrate }
    val bestM4a = m4aOrder.firstOrNull()
    val bestWebmA = webmA.firstOrNull()

    val audioOpts = m4aStd
        .distinctBy { it.averageBitrate }
        .map { x -> AudioOpt(x.averageBitrate, x, streamBytes(x), m4aOrder.filter { it !== x }) }

    val videoOpts = ArrayList<VideoOpt>()
    val vOnly = info.videoOnlyStreams.filter { it.deliveryMethod == http && it.isUrl && it.height > 0 }
    for ((h, list) in vOnly.groupBy { it.height }.entries.sortedByDescending { it.key }) {
        val avc = list.filter { it.codec?.startsWith("avc1") == true }.maxByOrNull { it.bitrate }
        val vp9 = list.filter { suffixOf(it) == "webm" && it.codec?.startsWith("vp9") == true }
            .maxByOrNull { it.bitrate }
        if (avc != null && bestM4a != null) {
            videoOpts += VideoOpt(h, avc, bestM4a, streamBytes(avc) + streamBytes(bestM4a), false, m4aOrder.drop(1))
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
