package app.feldkit.storage.filesystem.ntfs

/** NTFS "LZNT1" decompression (used by compressed files): 4 KB chunks, each plain or LZ77-style compressed. */
object Lznt1 {
    /** Decompresses [src] into [out]; bytes after the compressed data stay zero. Returns bytes produced. */
    fun decompress(src: ByteArray, srcLen: Int, out: ByteArray): Int {
        var sp = 0; var dp = 0
        while (sp + 2 <= srcLen && dp < out.size) {
            val hdr = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
            if (hdr == 0) break
            sp += 2
            val chunkLen = (hdr and 0xFFF) + 1
            val chunkEnd = minOf(sp + chunkLen, srcLen)
            val chunkStart = dp
            if (hdr and 0x8000 == 0) {                     // stored chunk
                val n = minOf(chunkEnd - sp, out.size - dp)
                System.arraycopy(src, sp, out, dp, n); dp += n; sp += chunkLen
                continue
            }
            while (sp < chunkEnd && dp < out.size) {
                val flags = src[sp++].toInt() and 0xFF
                for (bit in 0 until 8) {
                    if (sp >= chunkEnd || dp >= out.size) break
                    if (flags and (1 shl bit) == 0) { out[dp++] = src[sp++] }
                    else {
                        if (sp + 2 > chunkEnd) { sp = chunkEnd; break }
                        val tag = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8); sp += 2
                        var lg = 0; var i = dp - chunkStart - 1
                        while (i >= 0x10) { i = i shr 1; lg++ }
                        val delta = (tag shr (12 - lg)) + 1
                        val len = (tag and (0xFFF shr lg)) + 3
                        var from = dp - delta
                        if (from < chunkStart) return dp
                        for (k in 0 until len) { if (dp >= out.size) break; out[dp++] = out[from++] }
                    }
                }
            }
            sp = chunkEnd
        }
        return dp
    }
}
