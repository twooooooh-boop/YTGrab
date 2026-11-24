package com.ytgrab.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Joins a video file and an audio file (or just re-wraps one audio file) without re-encoding. */
object Muxer {
    private fun open(f: File, prefix: String): Pair<MediaExtractor, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) {
                ex.selectTrack(i)
                return ex to i
            }
        }
        ex.release()
        throw IllegalStateException("No $prefix track in ${f.name}")
    }

    fun mux(video: File?, audio: File?, out: File, webm: Boolean, token: CancelToken) {
        val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val muxer = MediaMuxer(out.absolutePath, format)
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        try {
            var vTrackOut = -1
            var aTrackOut = -1
            if (video != null) {
                val (ex, idx) = open(video, "video/")
                vEx = ex
                vTrackOut = muxer.addTrack(ex.getTrackFormat(idx))
            }
            if (audio != null) {
                val (ex, idx) = open(audio, "audio/")
                aEx = ex
                aTrackOut = muxer.addTrack(ex.getTrackFormat(idx))
            }
            muxer.start()

            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            fun pump(ex: MediaExtractor, track: Int) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) return
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }

            var vTime = vEx?.sampleTime ?: -1L
            var aTime = aEx?.sampleTime ?: -1L
            var counter = 0
            while (vTime >= 0 || aTime >= 0) {
                if ((counter++ and 0x3FF) == 0) token.check()
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                if (useVideo) {
                    pump(vEx!!, vTrackOut)
                    vTime = vEx.sampleTime
                } else {
                    pump(aEx!!, aTrackOut)
                    aTime = aEx.sampleTime
                }
            }
            muxer.stop()
        } finally {
            runCatching { vEx?.release() }
            runCatching { aEx?.release() }
            runCatching { muxer.release() }
        }
    }
}
