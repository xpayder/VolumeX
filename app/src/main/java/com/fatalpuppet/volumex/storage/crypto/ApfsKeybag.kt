package com.fatalpuppet.volumex.storage.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for the APFS media keybag and volume keybag.
 *
 * The keybag is stored in a special APFS object (type 0x6B626167 = "kbag") and
 * contains TLV (type-length-value) records describing how the volume encryption
 * keys are wrapped.
 *
 * Key TLV types used here:
 *   0x0001 = UUID (volume UUID, 16 bytes)
 *   0x0002 = PBKDF2 parameters (salt + iterations)
 *   0x0003 = HMAC / key check
 *   0x0006 = Wrapped VEK (variable length, depends on algorithm)
 *   0x0009 = Wrapped kek (KEK wrapped with another key)
 */
object ApfsKeybag {

    const val TAG_UUID: Int = 0x0001
    const val TAG_PBKDF2: Int = 0x0002
    const val TAG_HMAC: Int = 0x0003
    const val TAG_WRAPPED_VEK: Int = 0x0006
    const val TAG_WRAPPED_KEK: Int = 0x0009

    data class Pbkdf2Params(
        val salt: ByteArray,
        val iterations: Int,
        val keyLenBits: Int
    )

    data class WrappedKey(
        val tag: Int,
        val data: ByteArray
    )

    data class KeybagEntry(
        val uuid: ByteArray?,
        val pbkdf2Params: Pbkdf2Params?,
        val hmac: ByteArray?,
        val wrappedVek: ByteArray?,
        val wrappedKek: ByteArray?
    )

    /**
     * Parse a raw keybag blob into a list of entries.
     * The blob starts with a 16-byte header followed by TLV records.
     */
    fun parse(data: ByteArray): List<KeybagEntry> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val entries = mutableListOf<KeybagEntry>()

        // Skip keybag header (16 bytes: version(2) + num_entries(2) + data_len(4) + padding)
        if (buf.remaining() < 16) return entries
        val version = buf.getShort().toInt() and 0xFFFF
        val numEntries = buf.getShort().toInt() and 0xFFFF
        buf.getInt() // data_len
        buf.getLong() // padding/reserved

        repeat(numEntries) {
            if (buf.remaining() < 4) return@repeat
            val entry = parseEntry(buf)
            if (entry != null) entries.add(entry)
        }
        return entries
    }

    private fun parseEntry(buf: ByteBuffer): KeybagEntry? {
        // Each entry starts with a 16-byte UUID, then TLV records until next entry
        if (buf.remaining() < 16) return null

        var uuid: ByteArray? = null
        var pbkdf2: Pbkdf2Params? = null
        var hmac: ByteArray? = null
        var wrappedVek: ByteArray? = null
        var wrappedKek: ByteArray? = null

        // Read TLV records
        while (buf.remaining() >= 4) {
            val tag = buf.getShort().toInt() and 0xFFFF
            val len = buf.getShort().toInt() and 0xFFFF

            if (tag == 0 && len == 0) break // end sentinel
            if (buf.remaining() < len) break

            val value = ByteArray(len)
            buf.get(value)

            // Align to 4-byte boundary
            val padding = (4 - (len % 4)) % 4
            if (buf.remaining() >= padding) {
                buf.position(buf.position() + padding)
            }

            when (tag) {
                TAG_UUID -> uuid = value
                TAG_PBKDF2 -> pbkdf2 = parsePbkdf2(value)
                TAG_HMAC -> hmac = value
                TAG_WRAPPED_VEK -> wrappedVek = value
                TAG_WRAPPED_KEK -> wrappedKek = value
            }

            // Stop after we've seen a wrapped key (entry boundary heuristic)
            if (wrappedVek != null || wrappedKek != null) break
        }

        return KeybagEntry(uuid, pbkdf2, hmac, wrappedVek, wrappedKek)
    }

    private fun parsePbkdf2(data: ByteArray): Pbkdf2Params? {
        // Format: iterations(4 LE) + key_len(4 LE) + salt(remaining)
        if (data.size < 12) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val iterations = buf.getInt()
        val keyLenBytes = buf.getInt()
        val saltLen = data.size - 8
        val salt = ByteArray(saltLen)
        buf.get(salt)
        return Pbkdf2Params(salt, iterations, keyLenBytes * 8)
    }

    /**
     * Try to unwrap a wrapped VEK using RFC 3394 AES Key Wrap.
     * The wrapped key is 8 bytes longer than the plain key (integrity check value prepended).
     *
     * @param wrappedKey   The wrapped key bytes (from the keybag).
     * @param kek          The Key Encryption Key (derived from password).
     * @return             Unwrapped key bytes, or null on failure.
     */
    fun aesKeyUnwrap(wrappedKey: ByteArray, kek: ByteArray): ByteArray? {
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AESWrap")
            cipher.init(
                javax.crypto.Cipher.UNWRAP_MODE,
                javax.crypto.spec.SecretKeySpec(kek, "AES")
            )
            val unwrapped = cipher.unwrap(wrappedKey, "AES", javax.crypto.Cipher.SECRET_KEY)
            unwrapped.encoded
        } catch (e: Exception) {
            null // Wrong password → unwrap fails
        }
    }
}
