package com.ytgrab.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Own re-wrapper for the "fragmented MP4" (dash) files YouTube serves.
 * It reads the fragments and writes one ordinary MP4/M4A: [ftyp][mdat][moov], the index at the end.
 * Nothing is re-encoded. Used when Android's own muxer cannot read a stream.
 */
object Fmp4 {
    private class Box(val type: String, val start: Long, val size: Long, val header: Int) {
        val payload: Long get() = start + header
        val end: Long get() = start + size
    }

    private class Sample(val offset: Long, val size: Int, val duration: Long, val cto: Int, val sync: Boolean)

    private class Track(
        val handler: String,
        val timescale: Int,
        val stsd: ByteArray,
        val hdlr: ByteArray,
        val mediaHeader: ByteArray?,
        val dinf: ByteArray?,
        val width: Int,
        val height: Int,
        val trackId: Int,
        val trexDuration: Long,
        val trexSize: Int,
        val trexFlags: Int,
    ) {
        val samples = ArrayList<Sample>()
    }

    // ---------------------------------------------------------------- reading

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            raf.seek(pos)
            val s32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val tb = ByteArray(4)
            raf.readFully(tb)
            var header = 8
            var size = s32
            if (s32 == 1L) {
                size = raf.readLong()
                header = 16
            } else if (s32 == 0L) {
                size = to - pos
            }
            if (size < header || pos + size > to) break
            out.add(Box(String(tb, Charsets.ISO_8859_1), pos, size, header))
            pos += size
        }
        return out
    }

    private fun read(raf: RandomAccessFile, start: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        raf.seek(start)
        raf.readFully(b)
        return b
    }

    private fun payloadOf(raf: RandomAccessFile, b: Box): ByteArray = read(raf, b.payload, (b.size - b.header).toInt())
    private fun whole(raf: RandomAccessFile, b: Box): ByteArray = read(raf, b.start, b.size.toInt())
    private fun child(raf: RandomAccessFile, parent: Box, type: String): Box? =
        boxes(raf, parent.payload, parent.end).firstOrNull { it.type == type }

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL

    private fun i64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    /** Short text like "ftyp:24 moov:1203 sidx:96 moof:540 mdat:20103 ..." for error reports. */
    fun summary(f: File): String = try {
        RandomAccessFile(f, "r").use { raf ->
            val top = boxes(raf, 0, raf.length())
            val head = top.take(12).joinToString(" ") { "${it.type}:${it.size}" }
            head + (if (top.size > 12) " ... (${top.size} boxes)" else "")
        }
    } catch (e: Exception) {
        "unreadable"
    }

    private fun parseTrack(raf: RandomAccessFile, moov: Box): Track {
        val trak = boxes(raf, moov.payload, moov.end).firstOrNull { it.type == "trak" }
            ?: throw IOException("no track in the file")
        val tkhd = child(raf, trak, "tkhd") ?: throw IOException("no track header")
        val tk = payloadOf(raf, tkhd)
        val v1 = tk[0].toInt() == 1
        val trackId = i32(tk, if (v1) 20 else 12)
        val width = i32(tk, if (v1) 88 else 76)
        val height = i32(tk, if (v1) 92 else 80)

        val mdia = child(raf, trak, "mdia") ?: throw IOException("no media box")
        val mdhd = child(raf, mdia, "mdhd") ?: throw IOException("no media header")
        val md = payloadOf(raf, mdhd)
        val timescale = i32(md, if (md[0].toInt() == 1) 20 else 12)
        if (timescale <= 0) throw IOException("bad timescale")

        val hdlrBox = child(raf, mdia, "hdlr") ?: throw IOException("no handler box")
        val handler = String(read(raf, hdlrBox.payload + 8, 4), Charsets.ISO_8859_1)

        val minf = child(raf, mdia, "minf") ?: throw IOException("no minf box")
        val stbl = child(raf, minf, "stbl") ?: throw IOException("no stbl box")
        val stsd = child(raf, stbl, "stsd") ?: throw IOException("no sample description")
        val mh = child(raf, minf, "smhd") ?: child(raf, minf, "vmhd")
        val dinf = child(raf, minf, "dinf")

        var trexDur = 0L
        var trexSize = 0
        var trexFlags = 0
        val mvex = child(raf, moov, "mvex")
        if (mvex != null) {
            for (trex in boxes(raf, mvex.payload, mvex.end).filter { it.type == "trex" }) {
                val x = payloadOf(raf, trex)
                if (x.size >= 24 && i32(x, 4) == trackId) {
                    trexDur = u32(x, 12)
                    trexSize = i32(x, 16)
                    trexFlags = i32(x, 20)
                }
            }
        }
        return Track(
            handler, timescale, whole(raf, stsd), whole(raf, hdlrBox),
            mh?.let { whole(raf, it) }, dinf?.let { whole(raf, it) },
            width, height, trackId, trexDur, trexSize, trexFlags,
        )
    }

    private fun readFragments(raf: RandomAccessFile, top: List<Box>, t: Track) {
        val fileLen = raf.length()
        for (moof in top.filter { it.type == "moof" }) {
            var nextOffset = moof.end + 8
            for (traf in boxes(raf, moof.payload, moof.end).filter { it.type == "traf" }) {
                val kids = boxes(raf, traf.payload, traf.end)
                val tfhd = kids.firstOrNull { it.type == "tfhd" } ?: continue
                val h = payloadOf(raf, tfhd)
                val flags = i32(h, 0) and 0xFFFFFF
                if (i32(h, 4) != t.trackId) continue
                var p = 8
                var base = moof.start
                if (flags and 0x1 != 0) { base = i64(h, p); p += 8 }
                if (flags and 0x2 != 0) p += 4
                var defDur = t.trexDuration
                var defSize = t.trexSize
                var defFlags = t.trexFlags
                if (flags and 0x8 != 0) { defDur = u32(h, p); p += 4 }
                if (flags and 0x10 != 0) { defSize = i32(h, p); p += 4 }
                if (flags and 0x20 != 0) { defFlags = i32(h, p); p += 4 }

                for (trun in kids.filter { it.type == "trun" }) {
                    val r = payloadOf(raf, trun)
                    val rf = i32(r, 0) and 0xFFFFFF
                    val count = i32(r, 4)
                    if (count < 0 || count > 2_000_000) throw IOException("bad sample count")
                    var q = 8
                    var off = nextOffset
                    if (rf and 0x1 != 0) { off = base + i32(r, q); q += 4 }
                    var firstFlags = 0
                    if (rf and 0x4 != 0) { firstFlags = i32(r, q); q += 4 }
                    for (i in 0 until count) {
                        var dur = defDur
                        var size = defSize
                        var fl = defFlags
                        var cto = 0
                        if (rf and 0x100 != 0) { dur = u32(r, q); q += 4 }
                        if (rf and 0x200 != 0) { size = i32(r, q); q += 4 }
                        if (rf and 0x400 != 0) { fl = i32(r, q); q += 4 }
                        else if (i == 0 && rf and 0x4 != 0) fl = firstFlags
                        if (rf and 0x800 != 0) { cto = i32(r, q); q += 4 }
                        if (size <= 0) throw IOException("bad sample size")
                        if (off + size > fileLen) throw IOException("a sample reaches past the end of the file")
                        t.samples.add(Sample(off, size, dur, cto, (fl and 0x10000) == 0))
                        off += size
                    }
                    nextOffset = off
                }
            }
        }
    }

    // ---------------------------------------------------------------- writing

    private inline fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).block()
        return b.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var n = 8
        for (p in parts) n += p.size
        val b = ByteArrayOutputStream(n)
        val d = DataOutputStream(b)
        d.writeInt(n)
        d.write(type.toByteArray(Charsets.ISO_8859_1))
        for (p in parts) d.write(p)
        return b.toByteArray()
    }

    private fun full(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

    private fun matrix(d: DataOutputStream) {
        d.writeInt(0x00010000); d.writeInt(0); d.writeInt(0)
        d.writeInt(0); d.writeInt(0x00010000); d.writeInt(0)
        d.writeInt(0); d.writeInt(0); d.writeInt(0x40000000)
    }

    private fun ftyp(): ByteArray = box(
        "ftyp",
        bytes {
            write("isom".toByteArray(Charsets.ISO_8859_1))
            writeInt(0x200)
            write("isom".toByteArray(Charsets.ISO_8859_1))
            write("iso2".toByteArray(Charsets.ISO_8859_1))
            write("mp41".toByteArray(Charsets.ISO_8859_1))
        },
    )

    private fun mvhd(ts: Int, dur: Long, nextId: Int): ByteArray = full(
        "mvhd", 0, 0,
        bytes {
            writeInt(0); writeInt(0); writeInt(ts); writeInt(dur.toInt())
            writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
            matrix(this)
            for (i in 0 until 6) writeInt(0)
            writeInt(nextId)
        },
    )

    private fun runs(values: List<Long>): List<LongArray> {
        val out = ArrayList<LongArray>()
        for (v in values) {
            val last = out.lastOrNull()
            if (last != null && last[1] == v) last[0] = last[0] + 1 else out.add(longArrayOf(1L, v))
        }
        return out
    }

    private fun trak(tr: Track, id: Int, chunkOffset: Long, dur: Long, movieTs: Int): ByteArray {
        val movieDur = dur * movieTs / tr.timescale
        val ss = tr.samples

        val tkhd = full(
            "tkhd", 0, 3,
            bytes {
                writeInt(0); writeInt(0); writeInt(id); writeInt(0); writeInt(movieDur.toInt())
                writeInt(0); writeInt(0); writeShort(0); writeShort(0)
                writeShort(if (tr.handler == "soun") 0x0100 else 0); writeShort(0)
                matrix(this)
                writeInt(tr.width); writeInt(tr.height)
            },
        )
        val mdhd = full(
            "mdhd", 0, 0,
            bytes { writeInt(0); writeInt(0); writeInt(tr.timescale); writeInt(dur.toInt()); writeShort(0x55C4); writeShort(0) },
        )

        val stts = full("stts", 0, 0, bytes {
            val r = runs(ss.map { it.duration })
            writeInt(r.size)
            for (x in r) { writeInt(x[0].toInt()); writeInt(x[1].toInt()) }
        })
        val negative = ss.any { it.cto < 0 }
        val ctts = if (ss.any { it.cto != 0 }) full("ctts", if (negative) 1 else 0, 0, bytes {
            val r = runs(ss.map { it.cto.toLong() })
            writeInt(r.size)
            for (x in r) { writeInt(x[0].toInt()); writeInt(x[1].toInt()) }
        }) else null
        val allSync = ss.all { it.sync }
        val stss = if (tr.handler == "vide" && !allSync) full("stss", 0, 0, bytes {
            val idx = ArrayList<Int>()
            for ((i, s) in ss.withIndex()) if (s.sync) idx.add(i + 1)
            writeInt(idx.size)
            for (n in idx) writeInt(n)
        }) else null
        val stsc = full("stsc", 0, 0, bytes { writeInt(1); writeInt(1); writeInt(ss.size); writeInt(1) })
        val stsz = full("stsz", 0, 0, bytes {
            writeInt(0); writeInt(ss.size)
            for (s in ss) writeInt(s.size)
        })
        val stco = full("stco", 0, 0, bytes { writeInt(1); writeInt(chunkOffset.toInt()) })

        val parts = ArrayList<ByteArray>()
        parts.add(tr.stsd); parts.add(stts)
        if (ctts != null) parts.add(ctts)
        if (stss != null) parts.add(stss)
        parts.add(stsc); parts.add(stsz); parts.add(stco)
        val stbl = box("stbl", *parts.toTypedArray())

        val mh = tr.mediaHeader ?: if (tr.handler == "soun") {
            full("smhd", 0, 0, bytes { writeShort(0); writeShort(0) })
        } else {
            full("vmhd", 0, 1, bytes { writeShort(0); writeShort(0); writeShort(0); writeShort(0) })
        }
        val dinf = tr.dinf ?: box("dinf", full("dref", 0, 0, bytes { writeInt(1) }, full("url ", 0, 1)))
        val minf = box("minf", mh, dinf, stbl)
        val mdia = box("mdia", mdhd, tr.hdlr, minf)

        val minCto = ss.minOfOrNull { it.cto } ?: 0
        val edts = if (tr.handler == "vide" && minCto > 0) {
            box("edts", full("elst", 0, 0, bytes { writeInt(1); writeInt(movieDur.toInt()); writeInt(minCto); writeShort(1); writeShort(0) }))
        } else null
        return if (edts != null) box("trak", tkhd, edts, mdia) else box("trak", tkhd, mdia)
    }

    private fun buildMoov(tracks: List<Track>, chunkOffsets: List<Long>): ByteArray {
        val movieTs = 1000
        var movieDur = 0L
        val traks = ArrayList<ByteArray>()
        for ((i, tr) in tracks.withIndex()) {
            var dur = 0L
            for (s in tr.samples) dur += s.duration
            movieDur = maxOf(movieDur, dur * movieTs / tr.timescale)
            traks.add(trak(tr, i + 1, chunkOffsets[i], dur, movieTs))
        }
        val parts = ArrayList<ByteArray>()
        parts.add(mvhd(movieTs, movieDur, tracks.size + 1))
        parts.addAll(traks)
        return box("moov", *parts.toTypedArray())
    }

    private fun copy(src: RandomAccessFile, start: Long, len: Long, out: RandomAccessFile, buf: ByteArray) {
        src.seek(start)
        var left = len
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("the file ended early")
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Re-wraps the video and/or audio fragments into [out]. Returns how many samples were written. */
    fun remux(video: File?, audio: File?, out: File, token: CancelToken): Int {
        val tracks = ArrayList<Track>()
        val files = ArrayList<File>()
        for (f in listOfNotNull(video, audio)) {
            RandomAccessFile(f, "r").use { raf ->
                val top = boxes(raf, 0, raf.length())
                val moov = top.firstOrNull { it.type == "moov" }
                    ?: throw IOException("no index box in the file (boxes: ${top.take(8).joinToString(" ") { it.type }})")
                val tr = parseTrack(raf, moov)
                readFragments(raf, top, tr)
                if (tr.samples.isEmpty()) {
                    throw IOException("no samples found (boxes: ${top.take(10).joinToString(" ") { "${it.type}:${it.size}" }})")
                }
                tracks.add(tr)
                files.add(f)
            }
        }
        RandomAccessFile(out, "rw").use { o ->
            o.setLength(0)
            o.write(ftyp())
            val mdatStart = o.filePointer
            o.writeInt(0)
            o.write("mdat".toByteArray(Charsets.ISO_8859_1))
            val offsets = ArrayList<Long>()
            val buf = ByteArray(256 * 1024)
            for ((i, tr) in tracks.withIndex()) {
                token.check()
                offsets.add(o.filePointer)
                RandomAccessFile(files[i], "r").use { src ->
                    val ss = tr.samples
                    var a = 0
                    while (a < ss.size) {
                        var b = a
                        var len = ss[a].size.toLong()
                        while (b + 1 < ss.size && ss[b + 1].offset == ss[b].offset + ss[b].size) {
                            b++
                            len += ss[b].size
                        }
                        copy(src, ss[a].offset, len, o, buf)
                        a = b + 1
                        if ((a and 0x1FF) == 0) token.check()
                    }
                }
            }
            val mdatEnd = o.filePointer
            if (mdatEnd - mdatStart >= Int.MAX_VALUE) throw IOException("the file is too large for this format")
            o.seek(mdatStart)
            o.writeInt((mdatEnd - mdatStart).toInt())
            o.seek(mdatEnd)
            o.write(buildMoov(tracks, offsets))
        }
        return tracks.sumOf { it.samples.size }
    }
}
