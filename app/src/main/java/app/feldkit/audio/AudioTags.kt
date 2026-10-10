package app.feldkit.audio

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tempo written into a file by the tool that made it (ID3 TBPM, MP4 tmpo, FLAC / Vorbis BPM, WAV acid chunk). */
object AudioTags {
    class Tag(val bpm: Double, val source: String, /** Beats in a loop, from a WAV "acid" chunk. */ val beats: Int = 0)

    fun readBpm(ctx: Context, uri: Uri): Tag? {
        val head = ByteArray(1 shl 20)
        var n = 0
        try { ctx.contentResolver.openInputStream(uri)?.use { s -> while (n < head.size) { val r = s.read(head, n, head.size - n); if (r <= 0) break; n += r } } } catch (_: Exception) { return null }
        if (n < 12) return null
        return parse(head, n)
    }

    internal fun parse(b: ByteArray, n: Int): Tag? {
        val magic = String(b, 0, 4, Charsets.ISO_8859_1)
        return when {
            magic.startsWith("ID3") -> id3(b, 0, n)
            magic == "fLaC" -> flac(b, n)
            magic == "RIFF" -> riff(b, n)
            magic == "FORM" -> chunks(b, 12, n, false)
            String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(b, n)
            else -> null
        }
    }

    private fun parseBpm(s: String): Double? = s.trim().replace(',', '.').takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()?.takeIf { it in 20.0..400.0 }

    private fun id3(b: ByteArray, at: Int, n: Int): Tag? {
        if (at + 10 > n) return null
        val ver = b[at + 3].toInt()
        val size = ((b[at + 6].toInt() and 0x7F) shl 21) or ((b[at + 7].toInt() and 0x7F) shl 14) or ((b[at + 8].toInt() and 0x7F) shl 7) or (b[at + 9].toInt() and 0x7F)
        var p = at + 10
        val end = minOf(n, at + 10 + size)
        val idLen = if (ver == 2) 3 else 4; val hdr = if (ver == 2) 6 else 10
        while (p + hdr <= end) {
            val id = String(b, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fs = when (ver) {
                2 -> ((b[p + 3].toInt() and 0xFF) shl 16) or ((b[p + 4].toInt() and 0xFF) shl 8) or (b[p + 5].toInt() and 0xFF)
                4 -> ((b[p + 4].toInt() and 0x7F) shl 21) or ((b[p + 5].toInt() and 0x7F) shl 14) or ((b[p + 6].toInt() and 0x7F) shl 7) or (b[p + 7].toInt() and 0x7F)
                else -> ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            if (fs <= 0 || p + hdr + fs > end) break
            if (id == "TBPM" || id == "TBP") {
                val enc = b[p + hdr].toInt()
                val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                parseBpm(String(b, p + hdr + 1, fs - 1, cs).replace("\u0000", ""))?.let { return Tag(it, "ID3 tag") }
            }
            p += hdr + fs
        }
        return null
    }

    private fun flac(b: ByteArray, n: Int): Tag? {
        var p = 4
        while (p + 4 <= n) {
            val last = (b[p].toInt() and 0x80) != 0; val type = b[p].toInt() and 0x7F
            val len = ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (type == 4 && p + 4 + len <= n) {
                val bb = ByteBuffer.wrap(b, p + 4, len).order(ByteOrder.LITTLE_ENDIAN)
                val vendor = bb.int; bb.position(bb.position() + vendor)
                val count = bb.int
                for (i in 0 until count) {
                    if (bb.remaining() < 4) break
                    val l = bb.int; if (l < 0 || l > bb.remaining()) break
                    val s = String(b, bb.position(), l, Charsets.UTF_8); bb.position(bb.position() + l)
                    val eq = s.indexOf('=')
                    if (eq > 0 && (s.substring(0, eq).equals("BPM", true) || s.substring(0, eq).equals("TEMPO", true))) parseBpm(s.substring(eq + 1))?.let { return Tag(it, "FLAC tag") }
                }
            }
            if (last) break
            p += 4 + len
        }
        return null
    }

    private fun riff(b: ByteArray, n: Int): Tag? = chunks(b, 12, n, true)

    private fun chunks(b: ByteArray, from: Int, n: Int, little: Boolean): Tag? {
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        var p = from
        while (p + 8 <= n) {
            val id = String(b, p, 4, Charsets.ISO_8859_1); val sz = ByteBuffer.wrap(b, p + 4, 4).order(order).int
            if (sz < 0) break
            val body = p + 8
            if (id == "acid" && body + 24 <= n) {
                val bb = ByteBuffer.wrap(b, body, 24).order(ByteOrder.LITTLE_ENDIAN)
                val beats = bb.getInt(12); val tempo = bb.getFloat(20).toDouble()
                if (tempo in 20.0..400.0) return Tag(tempo, "WAV loop tag (acid)", beats)
            }
            if ((id == "ID3 " || id == "id3 ") && body + 10 <= n) id3(b, body, n)?.let { return it }
            p = body + sz + (sz and 1)
        }
        return null
    }

    /** iTunes-style 'tmpo' atom (16-bit integer) inside moov/udta/meta/ilst; found by scanning, which is enough for tags near the head. */
    private fun mp4(b: ByteArray, n: Int): Tag? {
        val key = byteArrayOf('t'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'o'.code.toByte())
        var i = 0
        while (i + 24 < n) {
            if (b[i] == key[0] && b[i + 1] == key[1] && b[i + 2] == key[2] && b[i + 3] == key[3] && String(b, i + 8, 4, Charsets.ISO_8859_1) == "data") {
                val v = ((b[i + 20].toInt() and 0xFF) shl 8) or (b[i + 21].toInt() and 0xFF)
                if (v in 20..400) return Tag(v.toDouble(), "MP4 tag")
            }
            i++
        }
        return null
    }
}
