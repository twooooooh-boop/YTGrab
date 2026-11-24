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

fun pickVideo(o: StreamOptions, height: Int): VideoOpt? {
    if (o.video.isEmpty()) return null
    if (height == 0) return o.video.first()
    return o.video.firstOrNull { it.height <= height } ?: o.video.last()
}

fun pickAudio(o: StreamOptions, bitrate: String): AudioOpt? {
    if (o.audio.isEmpty()) return null
    val target = bitrate.toIntOrNull() ?: return o.audio.first()
    return o.audio.firstOrNull { it.kbps <= target } ?: o.audio.last()
}
