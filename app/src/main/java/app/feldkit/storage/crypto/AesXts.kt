package app.feldkit.storage.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-XTS as used by APFS / FileVault: the tweak is a little-endian 128-bit counter that advances once per
 * 512-byte unit. The key is split in half (data key | tweak key), so 32 bytes = AES-128-XTS, 64 = AES-256-XTS.
 */
class AesXts(key: ByteArray) {
    init { require(key.size == 32 || key.size == 64) { "AES-XTS key must be 32 or 64 bytes, got ${key.size}" } }

    private val half = key.size / 2
    private val dataCipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").also { it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, half, "AES")) }
    private val tweakCipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, half, half, "AES")) }

    /** Decrypts one unit (multiple of 16 bytes) whose tweak value is [tweak]. */
    @Synchronized
    fun decryptUnit(src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int, tweak: Long) {
        require(len % 16 == 0)
        val t = ByteArray(16)
        var n = tweak
        for (i in 0..7) { t[i] = (n and 0xFF).toByte(); n = n ushr 8 }
        var cur = tweakCipher.doFinal(t)
        val tweaks = ByteArray(len)
        val buf = ByteArray(len)
        var o = 0
        while (o < len) {
            for (i in 0..15) { tweaks[o + i] = cur[i]; buf[o + i] = (src[srcOff + o + i].toInt() xor cur[i].toInt()).toByte() }
            var carry = 0
            for (i in 0..15) { val v = cur[i].toInt() and 0xFF; cur[i] = ((v shl 1) or carry).toByte(); carry = v ushr 7 }
            if (carry != 0) cur[0] = (cur[0].toInt() xor 0x87).toByte()
            o += 16
        }
        val dec = dataCipher.doFinal(buf)
        for (i in 0 until len) dst[dstOff + i] = (dec[i].toInt() xor tweaks[i].toInt()).toByte()
    }

    /** Decrypts [data] as consecutive [unit]-byte units; the first unit uses tweak [firstTweak]. */
    fun decrypt(data: ByteArray, firstTweak: Long, unit: Int = 512): ByteArray {
        val out = ByteArray(data.size)
        var o = 0
        var t = firstTweak
        while (o < data.size) {
            val n = minOf(unit, data.size - o)
            decryptUnit(data, o, n, out, o, t)
            o += n; t++
        }
        return out
    }
}
