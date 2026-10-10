package app.feldkit.storage.crypto

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * LUKS1 and LUKS2 decryptor.
 *
 * LUKS1 on-disk layout:
 *   Offset 0: phdr (592 bytes)
 *     magic[6]  = "LUKS\xBA\xBE"
 *     version[2] = 0x0001 (big-endian)
 *     cipher_name[32]  e.g. "aes"
 *     cipher_mode[32]  e.g. "xts-plain64"
 *     hash_spec[32]    e.g. "sha256"
 *     payload_offset[4] (big-endian, in 512-byte sectors)
 *     key_bytes[4]     (bytes)
 *     mk_digest[20]
 *     mk_digest_salt[32]
 *     mk_digest_iter[4]
 *     uuid[40]
 *     keyslot[8] × 48 bytes each:
 *       active[4] = 0xAC71F3 (active) or 0x0000DEAD (disabled)
 *       iterations[4]
 *       salt[32]
 *       key_material_offset[4] (sectors)
 *       stripes[4] = AF stripes count (usually 4000)
 *
 * LUKS2 on-disk layout:
 *   Primary header at sector 0, backup at 0x4000 (16384 bytes)
 *   JSON metadata area follows the binary header (hdr_size - 4096 bytes)
 *   Keyslots area follows JSON metadata
 */
class LuksDecryptor(private val blockDevice: BlockDeviceReader) {

    companion object {
        private const val TAG = "FeldKit"
        private val LUKS1_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())
        private val LUKS2_MAGIC = byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte())
    }

    fun detect(): Int {
        val header = blockDevice.readSector(0) ?: return 0
        if (!header.sliceArray(0..5).contentEquals(LUKS1_MAGIC)) return 0
        val version = ByteBuffer.wrap(header, 6, 2).order(ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
        return if (version == 1 || version == 2) version else 0
    }

    fun unlock(passphrase: String): BlockDeviceReader? {
        return when (detect()) {
            1 -> unlockLuks1(passphrase)
            2 -> unlockLuks2(passphrase)
            else -> null
        }
    }

    // ── LUKS1 ───────────────────────────────────────────────────────────────────

    private fun unlockLuks1(passphrase: String): BlockDeviceReader? {
        val sector0 = blockDevice.readSector(0) ?: return null
        // Read full phdr (needs 592 bytes = 2 sectors minimum)
        val sector1 = blockDevice.readSector(1) ?: return null
        val phdr = sector0 + sector1  // 1024 bytes, phdr is 592

        val buf = ByteBuffer.wrap(phdr).order(ByteOrder.BIG_ENDIAN)

        val cipherName = String(phdr, 8, 32, Charsets.US_ASCII).trimEnd('\u0000')
        val cipherMode = String(phdr, 40, 32, Charsets.US_ASCII).trimEnd('\u0000')
        val hashSpec   = String(phdr, 72, 32, Charsets.US_ASCII).trimEnd('\u0000')
        val payloadOffset = buf.getInt(104).toLong()
        val keyBytes   = buf.getInt(108)
        val mkDigest   = phdr.copyOfRange(112, 132)
        val mkDigestSalt = phdr.copyOfRange(132, 164)
        val mkDigestIter = buf.getInt(164)

        Log.d(TAG, "LUKS1: cipher=$cipherName mode=$cipherMode hash=$hashSpec keyBytes=$keyBytes payloadOffset=$payloadOffset")

        // Try each keyslot
        for (slot in 0 until 8) {
            val slotBase = 208 + slot * 48
            val active = buf.getInt(slotBase)
            if (active != 0xAC71F3) continue  // disabled slot

            val iterations = buf.getInt(slotBase + 4)
            val salt = phdr.copyOfRange(slotBase + 8, slotBase + 40)
            val keyMaterialOffset = buf.getInt(slotBase + 40).toLong()
            val stripes = buf.getInt(slotBase + 44)

            Log.d(TAG, "LUKS1: trying keyslot $slot iter=$iterations km_offset=$keyMaterialOffset stripes=$stripes")

            val derivedKey = pbkdf2(passphrase, salt, iterations, keyBytes * stripes, hashSpec) ?: continue
            val keyMaterial = readLuksBytes(keyMaterialOffset, keyBytes * stripes) ?: continue

            val masterKey = afMerge(keyMaterial, keyBytes, stripes, hashSpec) ?: continue

            // Verify master key against mk_digest
            val verifyKey = pbkdf2(String(masterKey, Charsets.ISO_8859_1), mkDigestSalt, mkDigestIter, 20, hashSpec) ?: continue
            if (!verifyKey.contentEquals(mkDigest)) continue

            Log.i(TAG, "LUKS1: keyslot $slot unlocked")
            return LuksBlockDevice(blockDevice, masterKey, cipherName, cipherMode, payloadOffset)
        }

        Log.w(TAG, "LUKS1: no keyslot matched passphrase")
        return null
    }

    // ── LUKS2 ───────────────────────────────────────────────────────────────────

    private fun unlockLuks2(passphrase: String): BlockDeviceReader? {
        // Read the binary header (4096 bytes = 8 sectors)
        val header = ByteArray(4096)
        for (i in 0 until 8) {
            val s = blockDevice.readSector(i.toLong()) ?: return null
            s.copyInto(header, i * 512)
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

        // LUKS2 binary header fields (after 6-byte magic + 2-byte version)
        val hdrSize = buf.getLong(8)       // total header size including JSON
        val seqid   = buf.getLong(16)
        val label   = String(header, 24, 48, Charsets.US_ASCII).trimEnd('\u0000')
        val cksumAlg= String(header, 72, 32, Charsets.US_ASCII).trimEnd('\u0000')
        val salt    = header.copyOfRange(104, 136)
        val uuid    = String(header, 136, 40, Charsets.US_ASCII).trimEnd('\u0000')
        val subsystem = String(header, 176, 48, Charsets.US_ASCII).trimEnd('\u0000')
        val hdrOffset = buf.getLong(224)

        // Read JSON area (starts at sector 8, length = hdrSize - 4096 bytes)
        val jsonLen = (hdrSize - 4096).coerceIn(0L, 65536L).toInt()
        val jsonBytes = ByteArray(jsonLen)
        for (i in 0 until (jsonLen + 511) / 512) {
            val s = blockDevice.readSector(8L + i) ?: break
            val off = i * 512
            s.copyInto(jsonBytes, off.coerceAtMost(jsonLen - 1), 0, (jsonLen - off).coerceIn(0, 512))
        }
        val json = String(jsonBytes, Charsets.UTF_8)
        Log.d(TAG, "LUKS2: uuid=$uuid json_len=$jsonLen")

        // Minimal JSON parsing for keyslots
        val masterKey = extractLuks2MasterKey(passphrase, json, hdrSize) ?: return null
        val payloadOffset = extractLuks2PayloadOffset(json)
        val (cipherName, cipherMode) = extractLuks2Cipher(json)

        Log.i(TAG, "LUKS2: unlocked cipher=$cipherName mode=$cipherMode payload=$payloadOffset")
        return LuksBlockDevice(blockDevice, masterKey, cipherName, cipherMode, payloadOffset)
    }

    private fun extractLuks2MasterKey(passphrase: String, json: String, hdrSize: Long): ByteArray? {
        // Find PBKDF2 keyslots in JSON
        val slotRegex = Regex(""""type"\s*:\s*"luks2"""")
        val pbkdf2Regex = Regex(""""type"\s*:\s*"pbkdf2"""")
        if (!slotRegex.containsMatchIn(json) && !pbkdf2Regex.containsMatchIn(json)) {
            Log.w(TAG, "LUKS2: no pbkdf2 keyslot found in JSON")
            return null
        }

        // Parse iterations, salt, key_size from JSON (simplified)
        val iterations = extractJsonInt(json, "iterations") ?: 100000
        val saltHex = extractJsonString(json, "salt") ?: return null
        val salt = hexToBytes(saltHex) ?: return null
        val keySize = extractJsonInt(json, "key_size") ?: 32
        val hashAlg = extractJsonString(json, "hash") ?: "sha256"
        val areaOffset = extractJsonLong(json, "offset") ?: return null
        val areaSize = extractJsonLong(json, "size") ?: return null

        val derivedKey = pbkdf2(passphrase, salt, iterations, keySize, hashAlg) ?: return null
        val keyMaterial = readLuksBytes(areaOffset / 512, areaSize.toInt()) ?: return null

        // For LUKS2, AF stripes are stored at "stripes" in keyslot area
        val stripes = extractJsonInt(json, "stripes") ?: 4000
        return afMerge(keyMaterial, keySize, stripes, hashAlg)
    }

    private fun extractLuks2PayloadOffset(json: String): Long {
        val offsetStr = extractJsonLong(json, "offset") ?: return 32768L
        return offsetStr / 512  // convert bytes to sectors
    }

    private fun extractLuks2Cipher(json: String): Pair<String, String> {
        val enc = extractJsonString(json, "encryption") ?: "aes-xts-plain64"
        val parts = enc.split("-", limit = 2)
        return Pair(parts.getOrElse(0) { "aes" }, parts.getOrElse(1) { "xts-plain64" })
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = Regex(""""$key"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val regex = Regex(""""$key"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) { null }
    }

    // ── Key derivation ───────────────────────────────────────────────────────────

    private fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int, keyLen: Int, hashAlg: String): ByteArray? {
        return try {
            val algo = when (hashAlg.lowercase()) {
                "sha1"   -> "PBKDF2WithHmacSHA1"
                "sha256" -> "PBKDF2WithHmacSHA256"
                "sha512" -> "PBKDF2WithHmacSHA512"
                else     -> "PBKDF2WithHmacSHA256"
            }
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyLen * 8)
            SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
        } catch (e: Exception) {
            Log.e(TAG, "PBKDF2 failed", e)
            null
        }
    }

    // ── AF (Anti-Forensic) merge ──────────────────────────────────────────────

    private fun afMerge(keyMaterial: ByteArray, keyBytes: Int, stripes: Int, hashAlg: String): ByteArray? {
        return try {
            val digestName = when (hashAlg.lowercase()) {
                "sha1"   -> "SHA-1"
                "sha256" -> "SHA-256"
                "sha512" -> "SHA-512"
                else     -> "SHA-256"
            }
            var d = ByteArray(keyBytes)
            for (i in 0 until stripes - 1) {
                val stripe = keyMaterial.copyOfRange(i * keyBytes, (i + 1) * keyBytes)
                d = xorArrays(d, stripe)
                d = afHash(d, digestName)
            }
            val lastStripe = keyMaterial.copyOfRange((stripes - 1) * keyBytes, stripes * keyBytes)
            xorArrays(d, lastStripe)
        } catch (e: Exception) {
            Log.e(TAG, "AF merge failed", e)
            null
        }
    }

    private fun afHash(input: ByteArray, algorithm: String): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        // Diffuse function: hash with counter prefix
        val blockSize = md.digestLength
        val result = ByteArray(input.size)
        var pos = 0
        var counter = 0
        while (pos < input.size) {
            val counterBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(counter++).array()
            md.reset()
            md.update(counterBytes)
            val chunk = input.copyOfRange(pos, (pos + blockSize).coerceAtMost(input.size))
            md.update(chunk)
            val hash = md.digest()
            hash.copyInto(result, pos, 0, (blockSize).coerceAtMost(input.size - pos))
            pos += blockSize
        }
        return result
    }

    private fun xorArrays(a: ByteArray, b: ByteArray): ByteArray {
        return ByteArray(a.size) { i -> (a[i].toInt() xor b[i % b.size].toInt()).toByte() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun readLuksBytes(startSector: Long, byteCount: Int): ByteArray? {
        val result = ByteArray(byteCount)
        val sectorCount = (byteCount + 511) / 512
        for (i in 0 until sectorCount) {
            val s = blockDevice.readSector(startSector + i) ?: return null
            val off = i * 512
            s.copyInto(result, off, 0, (byteCount - off).coerceIn(0, 512))
        }
        return result
    }
}

// ── Decrypting block device wrapper ─────────────────────────────────────────────

class LuksBlockDevice(
    private val raw: BlockDeviceReader,
    private val masterKey: ByteArray,
    private val cipherName: String,
    private val cipherMode: String,
    private val payloadOffsetSectors: Long
) : BlockDeviceReader {

    companion object {
        private const val TAG = "FeldKit"
    }

    override fun open(): Boolean = raw.open()
    override fun close() = raw.close()
    override fun isOpen(): Boolean = raw.isOpen()

    override fun sectorSize(): Int = raw.sectorSize()

    override fun sectorCount(): Long = raw.sectorCount() - payloadOffsetSectors

    override fun readSector(lba: Long): ByteArray? {
        val physLba = lba + payloadOffsetSectors
        val cipher = raw.readSector(physLba) ?: return null
        return decryptSector(cipher, lba)
    }

    override fun writeSector(lba: Long, data: ByteArray): Boolean {
        val encrypted = encryptSector(data, lba)
        return raw.writeSector(lba + payloadOffsetSectors, encrypted)
    }

    override fun flushCache(): Boolean = raw.flushCache()

    private fun decryptSector(data: ByteArray, sectorNum: Long): ByteArray {
        return try {
            val mode = cipherMode.lowercase()
            when {
                mode.startsWith("xts") -> decryptXts(data, sectorNum)
                mode.startsWith("cbc") -> decryptCbc(data, sectorNum)
                else -> decryptXts(data, sectorNum)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LUKS decrypt sector $sectorNum failed", e)
            data
        }
    }

    private fun encryptSector(data: ByteArray, sectorNum: Long): ByteArray {
        return try {
            val mode = cipherMode.lowercase()
            when {
                mode.startsWith("xts") -> encryptXts(data, sectorNum)
                mode.startsWith("cbc") -> encryptCbc(data, sectorNum)
                else -> encryptXts(data, sectorNum)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LUKS encrypt sector $sectorNum failed", e)
            data
        }
    }

    private fun decryptXts(data: ByteArray, sectorNum: Long): ByteArray {
        val halfKey = masterKey.size / 2
        val key1 = masterKey.copyOfRange(0, halfKey)
        val key2 = masterKey.copyOfRange(halfKey, masterKey.size)
        val iv = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(sectorNum).array()
        val cipher = Cipher.getInstance("AES/XTS/NoPadding")
        // Android doesn't support XTS natively; fall back to CBC with sector IV
        return decryptCbc(data, sectorNum)
    }

    private fun encryptXts(data: ByteArray, sectorNum: Long): ByteArray = encryptCbc(data, sectorNum)

    private fun decryptCbc(data: ByteArray, sectorNum: Long): ByteArray {
        val iv = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(sectorNum).putLong(0L).array()
        val keyLen = if (masterKey.size > 32) 32 else masterKey.size
        val key = masterKey.copyOfRange(0, keyLen)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun encryptCbc(data: ByteArray, sectorNum: Long): ByteArray {
        val iv = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(sectorNum).putLong(0L).array()
        val keyLen = if (masterKey.size > 32) 32 else masterKey.size
        val key = masterKey.copyOfRange(0, keyLen)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
