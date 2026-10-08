package com.fatalpuppet.volumex.storage.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Key derivation for FileVault / APFS encryption.
 *
 * The user password (or personal recovery key) is stretched using PBKDF2-HMAC-SHA256 to
 * produce the Key Encryption Key (KEK).  The KEK is then used to unwrap the Volume
 * Encryption Key (VEK) stored in the volume keybag.
 */
object ApfsKeyDerivation {

    /**
     * Derive a KEK candidate from a password using PBKDF2-HMAC-SHA256.
     *
     * @param password   The user password (UTF-8 chars).
     * @param salt       Salt bytes from the keybag PBKDF2 record.
     * @param iterations Iteration count from the keybag.
     * @param keyLenBits Desired output key length in bits (128 or 256).
     * @return Raw key bytes.
     */
    fun deriveKeyFromPassword(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLenBits: Int = 128
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLenBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Derive a KEK from a Personal Recovery Key (PRK).
     *
     * The PRK is typically a base-32 encoded string of 24 characters (16 bytes raw).
     * It is used directly (no PBKDF2 stretching in the simplest form), or with the
     * same PBKDF2 path depending on the firmware version.
     *
     * @param recoveryKeyBytes Raw bytes of the recovery key.
     * @param salt             Salt from keybag.
     * @param iterations       Iteration count from keybag (may be 1 for PRK path).
     * @param keyLenBits       Desired output key length in bits.
     */
    fun deriveKeyFromRecoveryKey(
        recoveryKeyBytes: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLenBits: Int = 128
    ): ByteArray {
        // Recovery key path: treat raw bytes as the "password" chars (Latin-1 mapping)
        val passwordChars = CharArray(recoveryKeyBytes.size) { recoveryKeyBytes[it].toInt().and(0xFF).toChar() }
        val spec = PBEKeySpec(passwordChars, salt, iterations, keyLenBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Decode a base-32 encoded recovery key string (RFC 4648, no padding).
     * Apple uses uppercase A-Z and digits 2-7.
     */
    fun decodeBase32RecoveryKey(encoded: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = encoded.uppercase().replace("-", "").replace(" ", "")
        val bits = StringBuilder()
        for (ch in clean) {
            val idx = alphabet.indexOf(ch)
            if (idx < 0) continue
            bits.append(idx.toString(2).padStart(5, '0'))
        }
        val byteCount = bits.length / 8
        return ByteArray(byteCount) { i ->
            bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        }
    }
}
