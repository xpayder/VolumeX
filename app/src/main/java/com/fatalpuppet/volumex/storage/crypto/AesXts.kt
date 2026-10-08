package com.fatalpuppet.volumex.storage.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-XTS block cipher implementation using Android JCE AES/ECB primitives.
 * AES-XTS is used by FileVault 2 (APFS encrypted volumes).
 *
 * The key is split in half: first half = K1 (data decryption), second half = K2 (tweak encryption).
 * Each 512-byte sector is independently decryptable using a tweak derived from the sector number.
 */
class AesXts(key: ByteArray) {

    init {
        require(key.size == 32 || key.size == 64) {
            "AES-XTS key must be 32 bytes (AES-128-XTS) or 64 bytes (AES-256-XTS), got ${key.size}"
        }
    }

    private val half = key.size / 2

    private val k1cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").also { c ->
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, half), "AES"))
    }

    private val k2cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").also { c ->
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(half, key.size), "AES"))
    }

    /**
     * Decrypt a single 512-byte sector.
     *
     * @param ciphertext The encrypted sector bytes (must be a multiple of 16).
     * @param sectorNum  Logical sector number used as the XTS tweak input.
     * @return Decrypted plaintext of the same length.
     */
    fun decryptSector(ciphertext: ByteArray, sectorNum: Long): ByteArray {
        require(ciphertext.size % 16 == 0) { "Sector size must be a multiple of 16" }

        // Encode sector number as 16-byte little-endian integer
        val tweakInput = ByteArray(16)
        var n = sectorNum
        for (i in 0..7) {
            tweakInput[i] = (n and 0xFF).toByte()
            n = n ushr 8
        }
        // upper 8 bytes stay 0

        // T = AES_K2_encrypt(sector_number)
        var tweak = k2cipher.doFinal(tweakInput)

        val result = ByteArray(ciphertext.size)
        var offset = 0

        while (offset < ciphertext.size) {
            // PP = C[offset..offset+16] XOR T
            val pp = ByteArray(16) { ciphertext[offset + it] xor tweak[it] }
            // CC = AES_K1_decrypt(PP)
            val cc = k1cipher.doFinal(pp)
            // P = CC XOR T
            for (i in 0..15) result[offset + i] = (cc[i] xor tweak[i])
            // Advance tweak: multiply by x in GF(2^128)
            tweak = gfMul2(tweak)
            offset += 16
        }

        return result
    }

    /**
     * Decrypt a full data block consisting of multiple 512-byte sectors.
     *
     * @param ciphertext   The encrypted block data.
     * @param firstSector  The sector number of the first sector in this block.
     * @param sectorSize   Size in bytes of each sector (default 512).
     */
    fun decryptBlock(ciphertext: ByteArray, firstSector: Long, sectorSize: Int = 512): ByteArray {
        require(sectorSize % 16 == 0)
        val result = ByteArray(ciphertext.size)
        val sectorCount = ciphertext.size / sectorSize
        for (s in 0 until sectorCount) {
            val offset = s * sectorSize
            val decrypted = decryptSector(
                ciphertext.copyOfRange(offset, offset + sectorSize),
                firstSector + s
            )
            decrypted.copyInto(result, offset)
        }
        return result
    }

    /**
     * GF(2^128) multiplication by x — used to advance the XTS tweak.
     * Polynomial: x^128 + x^7 + x^2 + x + 1  (0x87 feedback).
     * The bytes are treated as a 128-bit value in little-endian order.
     */
    private fun gfMul2(t: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 0..15) {
            val v = t[i].toInt() and 0xFF
            out[i] = ((v shl 1) or carry).toByte()
            carry = v ushr 7
        }
        if (carry != 0) out[0] = (out[0].toInt() xor 0x87).toByte()
        return out
    }
}
