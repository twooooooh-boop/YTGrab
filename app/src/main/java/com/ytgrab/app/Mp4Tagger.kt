package com.ytgrab.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes title / artist / cover art into an .mp4 or .m4a file (iTunes-style "ilst" atoms).
 * Works on files made by Android's MediaMuxer, where the 'moov' box is the last box in the file,
 * so nothing else in the file has to move.
 */
object Mp4Tagger {
    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(8 + payload.size)
        d.write(type)
        d.write(payload)
        return bos.toByteArray()
    }

    private fun box(type: String, payload: ByteArray) = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun dataBox(flags: Int, payload: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(flags)   // version 0 + 3 flag bytes (1 = text, 13 = JPEG, 14 = PNG)
        d.writeInt(0)       // locale
        d.write(payload)
        return box("data", bos.toByteArray())
    }

    private fun textItem(type: ByteArray, text: String) = box(type, dataBox(1, text.toByteArray(Charsets.UTF_8)))

    private fun buildUdta(title: String, artist: String, coverJpeg: ByteArray?): ByteArray {
        val items = ByteArrayOutputStream()
        val c = 0xA9.toByte()
        items.write(textItem(byteArrayOf(c, 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title))
        items.write(textItem(byteArrayOf(c, 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist))
        if (coverJpeg != null) items.write(box("covr", dataBox(13, coverJpeg)))
        val ilst = box("ilst", items.toByteArray())

        val hdlrBody = ByteArrayOutputStream()
        val h = DataOutputStream(hdlrBody)
        h.writeInt(0)                                   // version + flags
        h.writeInt(0)                                   // pre-defined
        h.write("mdir".toByteArray(Charsets.ISO_8859_1))
        h.write("appl".toByteArray(Charsets.ISO_8859_1))
        h.writeInt(0)
        h.writeInt(0)
        h.writeByte(0)                                  // empty name
        val hdlr = box("hdlr", hdlrBody.toByteArray())

        val metaBody = ByteArrayOutputStream()
        DataOutputStream(metaBody).writeInt(0)          // version + flags
        metaBody.write(hdlr)
        metaBody.write(ilst)
        return box("udta", box("meta", metaBody.toByteArray()))
    }

    /** Returns true when the tags were written. */
    fun tag(file: File, title: String, artist: String, coverJpeg: ByteArray?): Boolean {
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                var pos = 0L
                var moovPos = -1L
                var moovSize = 0L
                var moovHeaderOk = false
                var mdatPos = -1L
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val type = ByteArray(4)
                    raf.readFully(type)
                    val size = when (size32) {
                        1L -> raf.readLong()
                        0L -> len - pos
                        else -> size32
                    }
                    if (size < 8) return false
                    val t = String(type, Charsets.ISO_8859_1)
                    if (t == "moov") {
                        moovPos = pos
                        moovSize = size
                        moovHeaderOk = size32 > 1
                    }
                    if (t == "mdat" && mdatPos < 0) mdatPos = pos
                    pos += size
                }
                if (moovPos < 0 || !moovHeaderOk) return false
                if (moovPos + moovSize != len) return false      // moov must be the last box
                if (mdatPos < 0 || mdatPos > moovPos) return false
                val udta = buildUdta(title, artist, coverJpeg)
                raf.seek(moovPos)
                raf.writeInt((moovSize + udta.size).toInt())
                raf.seek(len)
                raf.write(udta)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
