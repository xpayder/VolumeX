package com.fatalpuppet.volumex.storage.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * APFS (FileVault) key handling, verified against volumes encrypted by macOS.
 *
 *  container superblock -> keylocker block (decrypted with container-UUID as XTS key, tweak = paddr*8)
 *     tag 2 (uuid = volume)  : DER blob holding the wrapped VEK
 *     tag 3 (uuid = volume)  : prange of the volume keybag
 *  volume keybag (decrypted with volume-UUID as XTS key)
 *     tag 3 entries          : DER blob = wrapped KEK + PBKDF2 iterations + salt (one per password / recovery key)
 *  KEK = PBKDF2-HMAC-SHA256(secret, salt, iterations, 32); VEK = RFC 3394 unwrap(KEK, wrapped VEK).
 *  Volume blocks are AES-XTS with the VEK, tweak = (paddr*8) + 512-byte-unit index.
 */
object ApfsCrypto {
    private const val TAG_VOLUME_KEY = 2
    private const val TAG_UNLOCK_RECORDS = 3
    const val KEYLOCKER_OFFSET = 1296   // nx_keylocker (prange_t) in the container superblock

    class KekRecord(val wrapped: ByteArray, val iterations: Int, val salt: ByteArray)
    class VolumeKeys(val wrappedVek: ByteArray, val keks: List<KekRecord>)

    private class KbEntry(val uuid: ByteArray, val tag: Int, val data: ByteArray)

    private fun entries(block: ByteArray): List<KbEntry> {
        val out = ArrayList<KbEntry>()
        if (block.size < 48) return out
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(34).toInt() and 0xFFFF
        var o = 48
        repeat(n) {
            if (o + 24 > block.size) return out
            val tag = b.getShort(o + 16).toInt() and 0xFFFF
            val klen = b.getShort(o + 18).toInt() and 0xFFFF
            if (o + 24 + klen > block.size) return out
            out.add(KbEntry(block.copyOfRange(o, o + 16), tag, block.copyOfRange(o + 24, o + 24 + klen)))
            o += (24 + klen + 15) / 16 * 16
        }
        return out
    }

    /** Minimal DER reader: returns the (tag, value) pairs of the elements directly inside [b]. */
    private fun der(b: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var o = 0
        while (o + 2 <= b.size) {
            val t = b[o].toInt() and 0xFF
            var l = b[o + 1].toInt() and 0xFF
            o += 2
            if (l and 0x80 != 0) {
                val cnt = l and 0x7F
                if (cnt > 4 || o + cnt > b.size) break
                l = 0
                repeat(cnt) { l = (l shl 8) or (b[o++].toInt() and 0xFF) }
            }
            if (l < 0 || o + l > b.size) break
            out.add(t to b.copyOfRange(o, o + l)); o += l
        }
        return out
    }

    private fun inner(blob: ByteArray): List<Pair<Int, ByteArray>> {
        val seq = der(blob).firstOrNull { it.first == 0x30 }?.second ?: return emptyList()
        return der(seq).firstOrNull { it.first == 0xA3 }?.second?.let { der(it) } ?: emptyList()
    }

    /**
     * Collects the key material of the volume with [volUuid]. [readBlock] returns the raw container block [paddr];
     * [keylockerAddr] comes from the container superblock.
     */
    fun loadVolumeKeys(readBlock: (Long) -> ByteArray?, containerUuid: ByteArray, keylockerAddr: Long, volUuid: ByteArray): VolumeKeys? {
        if (keylockerAddr <= 0) return null
        val kl = readBlock(keylockerAddr) ?: return null
        val container = entries(AesXts(containerUuid + containerUuid).decrypt(kl, keylockerAddr * 8))
        val vekBlob = container.firstOrNull { it.tag == TAG_VOLUME_KEY && it.uuid.contentEquals(volUuid) }?.data ?: return null
        val wrappedVek = inner(vekBlob).firstOrNull { it.first == 0x83 }?.second ?: return null
        val range = container.firstOrNull { it.tag == TAG_UNLOCK_RECORDS && it.uuid.contentEquals(volUuid) && it.data.size >= 16 }?.data ?: return null
        val bb = ByteBuffer.wrap(range).order(ByteOrder.LITTLE_ENDIAN)
        val kbAddr = bb.getLong(0)
        val kb = readBlock(kbAddr) ?: return null
        val keks = entries(AesXts(volUuid + volUuid).decrypt(kb, kbAddr * 8)).filter { it.tag == TAG_UNLOCK_RECORDS }.mapNotNull { e ->
            val f = inner(e.data).toMap()
            val wrapped = f[0x83] ?: return@mapNotNull null
            val iters = f[0x84]?.fold(0) { a, v -> (a shl 8) or (v.toInt() and 0xFF) } ?: return@mapNotNull null
            val salt = f[0x85] ?: return@mapNotNull null
            KekRecord(wrapped, iters, salt)
        }
        return VolumeKeys(wrappedVek, keks)
    }

    /** Tries [secret] (a password or the personal recovery key text) against every unlock record; returns the VEK or null. */
    fun unlock(keys: VolumeKeys, secret: String): ByteArray? {
        val pw = secret.toByteArray(Charsets.UTF_8)
        for (rec in keys.keks) {
            val kek = pbkdf2Sha256(pw, rec.salt, rec.iterations, 32)
            val kekPlain = aesUnwrap(kek, rec.wrapped) ?: continue
            val vek = aesUnwrap(kekPlain, keys.wrappedVek) ?: continue
            return vek
        }
        return null
    }

    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, outLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val out = ByteArray(outLen)
        var block = 1; var off = 0
        while (off < outLen) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte() }
            val n = minOf(t.size, outLen - off)
            System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    /** RFC 3394 AES key unwrap; null if the integrity check fails (wrong key). */
    fun aesUnwrap(kek: ByteArray, wrapped: ByteArray): ByteArray? {
        if (wrapped.size < 24 || wrapped.size % 8 != 0) return null
        val n = wrapped.size / 8 - 1
        val c = Cipher.getInstance("AES/ECB/NoPadding").also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES")) }
        val a = wrapped.copyOfRange(0, 8)
        val r = Array(n) { wrapped.copyOfRange(8 * (it + 1), 8 * (it + 2)) }
        val blk = ByteArray(16)
        for (j in 5 downTo 0) for (i in n downTo 1) {
            val t = (n * j + i).toLong()
            for (k in 0..7) blk[k] = (a[k].toLong() xor ((t ushr (8 * (7 - k))) and 0xFF)).toByte()
            System.arraycopy(r[i - 1], 0, blk, 8, 8)
            val d = c.doFinal(blk)
            System.arraycopy(d, 0, a, 0, 8); System.arraycopy(d, 8, r[i - 1], 0, 8)
        }
        if (a.any { it != 0xA6.toByte() }) return null
        val out = ByteArray(n * 8)
        for (i in 0 until n) System.arraycopy(r[i], 0, out, 8 * i, 8)
        return out
    }
}
