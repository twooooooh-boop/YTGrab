package com.ytgrab.app

import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A download that finished but the result is not real media (empty, stub, HTML page...). */
class BadMediaException(message: String) : IOException(message)

/** Final failure after every attempt; [details] is a full report the user can copy. */
class DownloadFailed(summary: String, val details: String) : IOException(summary)

/** Downloads one file with several parallel range requests and checks every byte arrived. */
object ChunkDownloader {

    private fun looksLikeNotMedia(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("html") || ct.contains("xml")
    }

    /** Real size of the file on the server (asks for 1 byte). */
    fun probeLength(url: String): Long {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", StreamUa.forUrl(url)).build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code != 206 && r.code != 200) {
                throw IOException("HTTP ${r.code} while checking the file size (YouTube client ${StreamUa.clientOf(url)})")
            }
            val ct = r.header("Content-Type").orEmpty()
            if (looksLikeNotMedia(ct)) throw IOException("Server sent \"$ct\" instead of media")
            val total = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
            if (total != null && total > 0) return total
            val cl = r.header("Content-Length")?.toLongOrNull()
            if (cl != null && cl > 1) return cl
        }
        throw IOException("Could not read the file size")
    }

    private fun writeFully(ch: FileChannel, data: ByteArray, len: Int, pos: Long) {
        val bb = ByteBuffer.wrap(data, 0, len)
        var p = pos
        while (bb.hasRemaining()) p += ch.write(bb, p)
    }

    fun download(
        url: String,
        total: Long,
        dest: File,
        threads: Int,
        token: CancelToken,
        chunk: Long,
        onBytes: (Long) -> Unit,
    ) {
        val raf = RandomAccessFile(dest, "rw")
        raf.setLength(total)
        val channel = raf.channel
        val chunks = ((total + chunk - 1) / chunk).toInt()
        val next = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val written = AtomicLong(0)
        val firstError = AtomicReference<Throwable?>(null)
        val n = threads.coerceIn(1, 16).coerceAtMost(chunks.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map {
                pool.submit(Runnable {
                    try {
                        while (true) {
                            token.check()
                            if (stop.get()) return@Runnable
                            val i = next.getAndIncrement()
                            if (i >= chunks) return@Runnable
                            val start = i.toLong() * chunk
                            val end = minOf(total - 1, start + chunk - 1)
                            var attempt = 0
                            while (true) {
                                token.check()
                                if (stop.get()) return@Runnable
                                var counted = 0L
                                try {
                                    fetch(url, start, end, channel, token, stop) { b -> counted += b; onBytes(b) }
                                    written.addAndGet(end - start + 1)
                                    break
                                } catch (e: IOException) {
                                    onBytes(-counted)
                                    attempt++
                                    if (attempt >= 4) throw e
                                    Thread.sleep(600L * attempt)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        if (t !is CancellationException) firstError.compareAndSet(null, t)
                        stop.set(true)
                        throw t
                    }
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    // remembered in firstError, reported below
                }
            }
            firstError.get()?.let { throw it }
            token.check()
            if (written.get() != total) {
                throw IOException("Incomplete download: got ${written.get()} of $total bytes")
            }
        } finally {
            pool.shutdownNow()
            runCatching { channel.close() }
            runCatching { raf.close() }
        }
    }

    private fun fetch(
        url: String,
        start: Long,
        end: Long,
        channel: FileChannel,
        token: CancelToken,
        stop: AtomicBoolean,
        onBytes: (Long) -> Unit,
    ) {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", StreamUa.forUrl(url))
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code != 206) throw IOException("HTTP ${r.code} (YouTube client ${StreamUa.clientOf(url)})")
            val ct = r.header("Content-Type").orEmpty()
            if (looksLikeNotMedia(ct)) throw IOException("Server sent \"$ct\" instead of media (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Empty response")
            val src = body.byteStream()
            val buf = ByteArray(64 * 1024)
            var pos = start
            while (true) {
                token.check()
                if (stop.get()) throw CancellationException("stopped")
                val read = src.read(buf)
                if (read < 0) break
                writeFully(channel, buf, read, pos)
                pos += read
                onBytes(read.toLong())
            }
            if (pos != end + 1) throw IOException("Short read")
        }
    }
}
