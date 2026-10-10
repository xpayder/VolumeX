package app.feldkit.storage.crypto

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LUKS1 and LUKS2 (dm-crypt) containers: header parsing, keyslot unlocking with a passphrase (PBKDF2, Argon2i / Argon2id),
 * anti-forensic key merge, and a sector device that decrypts AES-XTS / AES-CBC (plain, plain64, ESSIV) payloads.
 * Other ciphers (serpent, twofish) and detached headers are reported as unsupported.
 */
object Luks {
    private const val TAG = "FeldKit"

    class Slot(
        val keyBytes: Int, val stripes: Int, val areaOffset: Long /* bytes from the container start */, val areaMode: String,
        val kdf: String, val kdfHash: String, val iterations: Long, val salt: ByteArray, val memoryKiB: Int, val parallelism: Int, val afHash: String,
    )

    class Info(
        val version: Int, val cipher: String, val mode: String, val keyBytes: Int,
        val payloadOffset: Long /* bytes */, val payloadSize: Long /* bytes, -1 = to the end */, val sectorSize: Int, val ivTweak: Long,
        val slots: List<Slot>, val digestHash: String, val digestIterations: Long, val digestSalt: ByteArray, val digest: ByteArray, val uuid: String,
    ) {
        /** Null when this container can be opened, otherwise why not. */
        val unsupported: String? get() = when {
            cipher != "aes" -> "cipher '$cipher' is not supported (only AES)"
            !(mode.startsWith("xts-plain") || mode.startsWith("cbc-plain") || mode.startsWith("cbc-essiv")) -> "cipher mode '$mode' is not supported"
            slots.isEmpty() -> "no usable keyslot"
            else -> null
        }
    }

    // ── byte helpers ──

    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun be64(b: ByteArray, o: Int) = (be32(b, o) shl 32) or be32(b, o + 4)
    private fun cstr(b: ByteArray, o: Int, n: Int): String { var e = o; while (e < o + n && b[e].toInt() != 0) e++; return String(b, o, e - o, Charsets.US_ASCII) }

    private fun read(dev: BlockDeviceReader, startLba: Long, byteOffset: Long, len: Int): ByteArray? {
        val ss = dev.sectorSize()
        val first = startLba + byteOffset / ss; val last = startLba + (byteOffset + len - 1) / ss
        val d = dev.readSectors(first, (last - first + 1).toInt()) ?: return null
        val skip = (byteOffset % ss).toInt()
        return if (skip == 0 && d.size == len) d else d.copyOfRange(skip, skip + len)
    }

    // ── header ──

    fun load(dev: BlockDeviceReader, startLba: Long): Info? {
        val h = read(dev, startLba, 0, 4096) ?: return null
        if (h[0] != 'L'.code.toByte() || h[1] != 'U'.code.toByte() || h[2] != 'K'.code.toByte() || h[3] != 'S'.code.toByte() || (h[4].toInt() and 0xFF) != 0xBA || (h[5].toInt() and 0xFF) != 0xBE) return null
        return when (be16(h, 6)) { 1 -> loadV1(h); 2 -> loadV2(dev, startLba, h); else -> null }
    }

    private fun loadV1(h: ByteArray): Info? {
        val cipher = cstr(h, 8, 32); val mode = cstr(h, 40, 32); val hash = cstr(h, 72, 32)
        val payload = be32(h, 104); val keyBytes = be32(h, 108).toInt()
        val slots = ArrayList<Slot>()
        for (i in 0 until 8) {
            val b = 208 + i * 48
            if (be32(h, b) != 0x00AC71F3L) continue
            slots.add(Slot(keyBytes, be32(h, b + 44).toInt(), be32(h, b + 40) * 512, "$cipher-$mode", "pbkdf2", hash, be32(h, b + 4), h.copyOfRange(b + 8, b + 40), 0, 0, hash))
        }
        return Info(1, cipher, mode, keyBytes, payload * 512, -1, 512, 0, slots, hash, be32(h, 164), h.copyOfRange(132, 164), h.copyOfRange(112, 132), cstr(h, 168, 40))
    }

    private fun loadV2(dev: BlockDeviceReader, startLba: Long, h: ByteArray): Info? {
        val hdrSize = be64(h, 8).coerceIn(4096L, 4L shl 20).toInt()
        val full = if (hdrSize <= h.size) h else read(dev, startLba, 0, hdrSize) ?: return null
        var end = 4096; while (end < hdrSize && full[end].toInt() != 0) end++
        val json = Json.parse(String(full, 4096, end - 4096, Charsets.UTF_8)) as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST") fun obj(m: Any?) = m as? Map<String, Any?>
        val keyslots = obj(json["keyslots"]) ?: return null
        val segments = obj(json["segments"]) ?: return null
        val digests = obj(json["digests"]) ?: return null
        val seg = segments.values.mapNotNull { obj(it) }.firstOrNull { it["type"] == "crypt" } ?: return null
        val enc = (seg["encryption"] as? String) ?: return null
        val (cipher, mode) = enc.substringBefore('-') to enc.substringAfter('-')
        val dig = digests.values.mapNotNull { obj(it) }.firstOrNull() ?: return null
        val slots = ArrayList<Slot>()
        for ((_, v) in keyslots) {
            val ks = obj(v) ?: continue
            if (ks["type"] != "luks2") continue
            val af = obj(ks["af"]) ?: continue; val area = obj(ks["area"]) ?: continue; val kdf = obj(ks["kdf"]) ?: continue
            slots.add(Slot(
                (ks["key_size"] as Number).toInt(), (af["stripes"] as Number).toInt(), (area["offset"] as String).toLong(), area["encryption"] as String,
                kdf["type"] as String, (kdf["hash"] as? String) ?: "sha256", ((kdf["iterations"] ?: kdf["time"]) as Number).toLong(), Base64.getDecoder().decode(kdf["salt"] as String),
                ((kdf["memory"] as? Number)?.toInt()) ?: 0, ((kdf["cpus"] as? Number)?.toInt()) ?: 1, (af["hash"] as? String) ?: "sha256",
            ))
        }
        val size = (seg["size"] as? String)?.toLongOrNull() ?: -1L
        return Info(
            2, cipher, mode, (seg["key_size"] as? Number)?.toInt() ?: slots.firstOrNull()?.keyBytes ?: 0, (seg["offset"] as String).toLong(), size,
            ((seg["sector_size"] as? Number)?.toInt() ?: 512), ((seg["iv_tweak"] as? String)?.toLongOrNull() ?: 0L), slots,
            (dig["hash"] as? String) ?: "sha256", (dig["iterations"] as? Number)?.toLong() ?: 0L, Base64.getDecoder().decode(dig["salt"] as String), Base64.getDecoder().decode(dig["digest"] as String), cstr(h, 168, 40),
        )
    }

    // ── key derivation ──

    private fun macName(hash: String) = when (hash.lowercase()) { "sha1" -> "HmacSHA1"; "sha256" -> "HmacSHA256"; "sha512" -> "HmacSHA512"; else -> null }
    private fun digestName(hash: String) = when (hash.lowercase()) { "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; "sha512" -> "SHA-512"; else -> null }

    fun pbkdf2(hash: String, password: ByteArray, salt: ByteArray, iterations: Long, outLen: Int): ByteArray? {
        val mac = Mac.getInstance(macName(hash) ?: return null)
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, mac.algorithm))
        val hLen = mac.macLength; val blocks = (outLen + hLen - 1) / hLen
        val out = ByteArray(outLen)
        for (i in 1..blocks) {
            mac.update(salt); mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (r in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            System.arraycopy(t, 0, out, (i - 1) * hLen, minOf(hLen, outLen - (i - 1) * hLen))
        }
        return out
    }

    /** Anti-forensic merge: the master key is spread over [stripes] stripes of [keyBytes] each. */
    private fun afMerge(data: ByteArray, keyBytes: Int, stripes: Int, hash: String): ByteArray? {
        val md = MessageDigest.getInstance(digestName(hash) ?: return null)
        fun diffuse(src: ByteArray): ByteArray {
            val dLen = md.digestLength; val out = ByteArray(src.size)
            val full = src.size / dLen; val rem = src.size % dLen
            for (i in 0..(if (rem > 0) full else full - 1)) {
                md.reset(); md.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())); md.update(src, i * dLen, minOf(dLen, src.size - i * dLen))
                val d = md.digest(); System.arraycopy(d, 0, out, i * dLen, minOf(dLen, src.size - i * dLen))
            }
            return out
        }
        var d = ByteArray(keyBytes)
        for (s in 0 until stripes - 1) {
            for (i in 0 until keyBytes) d[i] = (d[i].toInt() xor data[s * keyBytes + i].toInt()).toByte()
            d = diffuse(d)
        }
        for (i in 0 until keyBytes) d[i] = (d[i].toInt() xor data[(stripes - 1) * keyBytes + i].toInt()).toByte()
        return d
    }

    /** Largest Argon2 memory we try to allocate (KiB): a keyslot asking for more is reported instead of crashing. */
    @Volatile var maxArgonKiB = 2_000_000
    /** Why the last unlock failed when it was not simply a wrong passphrase (shown by the unlock screen); null otherwise. */
    @Volatile var lastFailure: String? = null

    /** The master key, or null for a wrong passphrase / an unusable container. [progress] gets one call per tried keyslot. */
    fun unlock(dev: BlockDeviceReader, startLba: Long, info: Info, passphrase: String, progress: ((String) -> Unit)? = null): ByteArray? {
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        lastFailure = null
        for ((idx, slot) in info.slots.withIndex()) {
            progress?.invoke("Trying keyslot ${idx + 1} of ${info.slots.size}")
            val derived: ByteArray = try {
                when (slot.kdf) {
                    "pbkdf2" -> pbkdf2(slot.kdfHash, pw, slot.salt, slot.iterations, slot.keyBytes)
                    "argon2i", "argon2id" -> {
                        if (slot.memoryKiB > maxArgonKiB) { Log.w(TAG, "LUKS: keyslot needs ${slot.memoryKiB / 1024} MiB for Argon2"); lastFailure = "This volume needs ${slot.memoryKiB / 1024} MB of memory to check the password, more than this phone can give"; null }
                        else Argon2.hash(if (slot.kdf == "argon2i") 1 else 2, pw, slot.salt, slot.iterations.toInt(), slot.memoryKiB, slot.parallelism.coerceAtLeast(1), slot.keyBytes)
                    }
                    else -> null
                }
            } catch (e: OutOfMemoryError) { Log.w(TAG, "LUKS: out of memory for Argon2"); lastFailure = "Not enough memory to check the password of this volume"; null } catch (e: java.io.IOException) { Log.w(TAG, "LUKS: cannot create working memory", e); lastFailure = "Could not create working memory (is there free storage?)"; null } ?: continue
            val areaLen = ((slot.keyBytes * slot.stripes + 511) / 512) * 512
            val enc = read(dev, startLba, slot.areaOffset, areaLen) ?: continue
            val areaCipher = SectorCipher(slot.areaMode.substringAfter('-'), derived, 512)
            val dec = ByteArray(areaLen)
            for (s in 0 until areaLen / 512) areaCipher.decrypt(enc, s * 512, dec, s * 512, s.toLong())
            val master = afMerge(dec, slot.keyBytes, slot.stripes, slot.afHash) ?: continue
            val check = pbkdf2(info.digestHash, master, info.digestSalt, info.digestIterations, info.digest.size) ?: continue
            if (check.contentEquals(info.digest)) return master
        }
        return null
    }

    /** One of the dm-crypt sector ciphers: XTS-plain(64), CBC-plain(64) and CBC-ESSIV:sha256. [unit] is the encryption sector size. */
    class SectorCipher(mode: String, private val key: ByteArray, private val unit: Int) {
        private val m = mode.lowercase()
        private val xts = if (m.startsWith("xts")) AesXts(key) else null
        private val essivCipher: Cipher? = if (m.contains("essiv")) {
            val h = MessageDigest.getInstance(if (m.endsWith("sha1")) "SHA-1" else "SHA-256").digest(key)
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(h.copyOf(if (h.size >= 32) 32 else 16), "AES")) }
        } else null
        private val cbcKey = SecretKeySpec(if (key.size > 32) key.copyOf(32) else key, "AES")

        /** Decrypts the [unit]-byte sector at [src]/[srcOff] whose index is [sector] (in units of [unit]). */
        @Synchronized fun decrypt(src: ByteArray, srcOff: Int, dst: ByteArray, dstOff: Int, sector: Long) {
            if (xts != null) { xts.decryptUnit(src, srcOff, unit, dst, dstOff, sector); return }
            val iv = ByteArray(16)
            if (m.contains("plain64") || essivCipher != null) { for (i in 0..7) iv[i] = (sector ushr (8 * i)).toByte() } else for (i in 0..3) iv[i] = (sector ushr (8 * i)).toByte()
            val realIv = essivCipher?.doFinal(iv) ?: iv
            Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, cbcKey, IvParameterSpec(realIv)) }.doFinal(src, srcOff, unit, dst, dstOff)
        }
    }
}

/** The decrypted payload of an unlocked LUKS container, as 512-byte sectors. */
class LuksDevice(private val raw: BlockDeviceReader, private val startLba: Long, private val info: Luks.Info, key: ByteArray) : BlockDeviceReader {
    private val cipher = Luks.SectorCipher(info.mode, key, info.sectorSize)
    private val payloadSectors = info.payloadOffset / 512
    private val total: Long = if (info.payloadSize >= 0) info.payloadSize / 512 else (raw.sectorCount() - startLba - payloadSectors).coerceAtLeast(0)
    override fun open() = true
    override fun close() {}
    override fun isOpen() = true
    override fun sectorSize() = 512
    override fun sectorCount() = total

    override fun readSector(lba: Long): ByteArray? = readSectors(lba, 1)

    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        if (startLba < 0 || startLba + count > total) return null
        val unitSectors = info.sectorSize / 512
        val firstUnit = startLba / unitSectors; val lastUnit = (startLba + count - 1) / unitSectors
        val raw = raw.readSectors(this.startLba + payloadSectors + firstUnit * unitSectors, ((lastUnit - firstUnit + 1) * unitSectors).toInt()) ?: return null
        val plain = ByteArray(raw.size)
        for (u in 0..(lastUnit - firstUnit).toInt()) cipher.decrypt(raw, u * info.sectorSize, plain, u * info.sectorSize, firstUnit + u + info.ivTweak)
        val skip = ((startLba - firstUnit * unitSectors) * 512).toInt()
        return plain.copyOfRange(skip, skip + count * 512)
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null): enough for the LUKS2 metadata area. */
internal object Json {
    fun parse(text: String): Any? { val p = Parser(text); p.ws(); return p.value() }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }; while (true) { ws(); val k = string(); ws(); i++; m[k] = value(); ws(); if (s[i] == ',') i++ else { i++; break } }; m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }; while (true) { l.add(value()); ws(); if (s[i] == ',') i++ else { i++; break } }; l }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+')) i++; val t = s.substring(st, i); t.toLongOrNull() ?: t.toDouble() }
            }
        }
        fun string(): String {
            i++; val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } } else sb.append(s[i])
                i++
            }
            i++; return sb.toString()
        }
    }
}
