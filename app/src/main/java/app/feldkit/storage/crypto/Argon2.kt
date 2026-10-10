package app.feldkit.storage.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/** BLAKE2b (RFC 7693), unkeyed, output 1..64 bytes. */
internal object Blake2b {
    private val IV = longArrayOf(
        0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(), 0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
        0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(), 0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong(),
    )
    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4), intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13), intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11), intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5), intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    )

    private fun rotr(x: Long, n: Int) = (x ushr n) or (x shl (64 - n))

    private fun compress(h: LongArray, block: ByteArray, off: Int, t: Long, last: Boolean) {
        val m = LongArray(16)
        for (i in 0 until 16) { var v = 0L; for (j in 7 downTo 0) v = (v shl 8) or (block[off + i * 8 + j].toLong() and 0xFF); m[i] = v }
        val v = LongArray(16)
        for (i in 0 until 8) { v[i] = h[i]; v[i + 8] = IV[i] }
        v[12] = v[12] xor t
        if (last) v[14] = v[14].inv()
        fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] = v[a] + v[b] + x; v[d] = rotr(v[d] xor v[a], 32)
            v[c] = v[c] + v[d]; v[b] = rotr(v[b] xor v[c], 24)
            v[a] = v[a] + v[b] + y; v[d] = rotr(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]; v[b] = rotr(v[b] xor v[c], 63)
        }
        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]]); g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]]); g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    fun hash(data: ByteArray, outLen: Int): ByteArray {
        val h = IV.copyOf(); h[0] = h[0] xor (0x01010000L or outLen.toLong())
        var off = 0; var t = 0L
        while (data.size - off > 128) { t += 128; compress(h, data, off, t, false); off += 128 }
        val last = ByteArray(128); System.arraycopy(data, off, last, 0, data.size - off)
        t += (data.size - off).toLong(); compress(h, last, 0, t, true)
        val out = ByteArray(outLen)
        for (i in 0 until outLen) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }
}

/** Argon2 (RFC 9106, version 1.3): types 0 = d, 1 = i, 2 = id. Memory is held off the Java heap so volumes using ~1 GiB can be opened. */
object Argon2 {
    /** Where large working memory lives (a file-backed mapping, so it does not count against the app heap). */
    @Volatile var tempDir: java.io.File? = null
    private const val HEAP_LIMIT_BYTES = 96 shl 20
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** H': variable-length hash built from BLAKE2b. */
    private fun hPrime(outLen: Int, input: ByteArray): ByteArray {
        if (outLen <= 64) return Blake2b.hash(le32(outLen) + input, outLen)
        val out = ByteArray(outLen)
        var v = Blake2b.hash(le32(outLen) + input, 64)
        System.arraycopy(v, 0, out, 0, 32); var pos = 32
        var remaining = outLen - 32
        while (remaining > 64) { v = Blake2b.hash(v, 64); System.arraycopy(v, 0, out, pos, 32); pos += 32; remaining -= 32 }
        v = Blake2b.hash(v, remaining); System.arraycopy(v, 0, out, pos, remaining)
        return out
    }

    private fun fBla(x: Long, y: Long) = x + y + 2L * (x and 0xFFFFFFFFL) * (y and 0xFFFFFFFFL)
    private fun rotr(x: Long, n: Int) = (x ushr n) or (x shl (64 - n))

    private fun round(v: LongArray, i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int, i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int) {
        fun g(a: Int, b: Int, c: Int, d: Int) {
            v[a] = fBla(v[a], v[b]); v[d] = rotr(v[d] xor v[a], 32)
            v[c] = fBla(v[c], v[d]); v[b] = rotr(v[b] xor v[c], 24)
            v[a] = fBla(v[a], v[b]); v[d] = rotr(v[d] xor v[a], 16)
            v[c] = fBla(v[c], v[d]); v[b] = rotr(v[b] xor v[c], 63)
        }
        g(i0, i4, i8, i12); g(i1, i5, i9, i13); g(i2, i6, i10, i14); g(i3, i7, i11, i15)
        g(i0, i5, i10, i15); g(i1, i6, i11, i12); g(i2, i7, i8, i13); g(i3, i4, i9, i14)
    }

    /** next = G(prev, ref), optionally XORed into the old content of next (passes after the first). */
    private fun fillBlock(prev: LongArray, ref: LongArray, next: LongArray, withXor: Boolean) {
        val r = LongArray(128); val tmp = LongArray(128)
        for (i in 0 until 128) { r[i] = ref[i] xor prev[i]; tmp[i] = r[i] }
        if (withXor) for (i in 0 until 128) tmp[i] = tmp[i] xor next[i]
        for (i in 0 until 8) { val b = 16 * i; round(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15) }
        for (i in 0 until 8) { val b = 2 * i; round(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113) }
        for (i in 0 until 128) next[i] = tmp[i] xor r[i]
    }

    fun hash(type: Int, password: ByteArray, salt: ByteArray, timeCost: Int, memoryKiB: Int, parallelism: Int, tagLength: Int, secret: ByteArray = ByteArray(0), associated: ByteArray = ByteArray(0)): ByteArray {
        val p = parallelism
        val m = maxOf(memoryKiB, 8 * p) / (4 * p) * (4 * p)
        val laneLength = m / p; val seg = laneLength / 4
        val h0in = ByteBuffer.allocate(40 + password.size + salt.size + secret.size + associated.size).order(ByteOrder.LITTLE_ENDIAN)
        h0in.putInt(p).putInt(tagLength).putInt(memoryKiB).putInt(timeCost).putInt(0x13).putInt(type).putInt(password.size).put(password).putInt(salt.size).put(salt).putInt(secret.size).put(secret).putInt(associated.size).put(associated)
        val h0 = Blake2b.hash(h0in.array().copyOf(h0in.position()), 64)
        var tmpFile: java.io.File? = null
        val bytes = m.toLong() * 1024
        val buf: ByteBuffer = if (bytes <= HEAP_LIMIT_BYTES) ByteBuffer.allocateDirect(bytes.toInt()) else {
            val f = java.io.File.createTempFile("argon2-", ".mem", tempDir ?: java.io.File(System.getProperty("java.io.tmpdir")!!)); tmpFile = f
            java.io.RandomAccessFile(f, "rw").use { raf -> raf.setLength(bytes); raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, bytes) }
        }
        val mem: LongBuffer = buf.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        try {
        fun load(index: Int, dst: LongArray) { mem.position(index * 128); mem.get(dst) }
        fun store(index: Int, src: LongArray) { mem.position(index * 128); mem.put(src) }
        fun blockFrom(bytes: ByteArray): LongArray { val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); return LongArray(128) { b.getLong(it * 8) } }
        for (l in 0 until p) {
            store(l * laneLength, blockFrom(hPrime(1024, h0 + le32(0) + le32(l))))
            store(l * laneLength + 1, blockFrom(hPrime(1024, h0 + le32(1) + le32(l))))
        }
        val prevB = LongArray(128); val refB = LongArray(128); val curB = LongArray(128)
        val zero = LongArray(128); val input = LongArray(128); val address = LongArray(128)
        for (pass in 0 until timeCost) for (slice in 0 until 4) for (lane in 0 until p) {
            val independent = type == 1 || (type == 2 && pass == 0 && slice < 2)
            var start = 0
            if (independent) { input.fill(0); input[0] = pass.toLong(); input[1] = lane.toLong(); input[2] = slice.toLong(); input[3] = m.toLong(); input[4] = timeCost.toLong(); input[5] = type.toLong() }
            fun nextAddresses() { input[6]++; fillBlock(zero, input, address, false); fillBlock(zero, address, address, false) }
            if (pass == 0 && slice == 0) { start = 2; if (independent) nextAddresses() }
            for (i in start until seg) {
                val cur = lane * laneLength + slice * seg + i
                val prev = if (cur % laneLength == 0) cur + laneLength - 1 else cur - 1
                val rand: Long
                if (independent) { if (i % 128 == 0) nextAddresses(); rand = address[i % 128] } else { mem.position(prev * 128); rand = mem.get() }
                val j1 = rand and 0xFFFFFFFFL; val j2 = rand ushr 32
                val refLane = if (pass == 0 && slice == 0) lane else (j2 % p).toInt()
                val area: Long = if (refLane == lane) {
                    if (pass == 0) { if (slice == 0) (i - 1).toLong() else (slice * seg + i - 1).toLong() } else (laneLength - seg + i - 1).toLong()
                } else {
                    if (pass == 0) (slice * seg - (if (i == 0) 1 else 0)).toLong() else (laneLength - seg - (if (i == 0) 1 else 0)).toLong()
                }
                var rel = (j1 * j1) ushr 32
                rel = area - 1 - ((area * rel) ushr 32)
                val startPos = if (pass == 0) 0 else if (slice == 3) 0 else (slice + 1) * seg
                val abs = ((startPos + rel) % laneLength).toInt()
                load(prev, prevB); load(refLane * laneLength + abs, refB)
                if (pass != 0) load(cur, curB)
                fillBlock(prevB, refB, curB, pass != 0)
                store(cur, curB)
            }
        }
            val c = LongArray(128); val tmp = LongArray(128)
            for (l in 0 until p) { load(l * laneLength + laneLength - 1, tmp); for (i in 0 until 128) c[i] = c[i] xor tmp[i] }
            val cb = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN); for (x in c) cb.putLong(x)
            return hPrime(tagLength, cb.array())
        } finally { tmpFile?.delete() }
    }
}
