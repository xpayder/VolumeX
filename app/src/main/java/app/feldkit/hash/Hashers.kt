package app.feldkit.hash

import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.CRC32

/** Streaming hash with a canonical big-endian digest, the way `xxhsum`, `shasum` and `md5` print them. */
interface Hasher {
    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off)
    fun digest(): ByteArray
    fun release() {}
}

/** Everything the app can compute while a file streams past. [label] is what the user sees; [mhl] is the ASC MHL element name. */
enum class HashAlgo(val label: String, val mhl: String?, val fileExt: String) {
    XXH64("xxh64", "xxh64", "xxh64"),
    XXH3_64("xxh3 (64-bit)", "xxh3", "xxh3"),
    XXH3_128("xxh128", "xxh128", "xxh128"),
    MD5("MD5", "md5", "md5"),
    SHA1("SHA-1", "sha1", "sha1"),
    SHA256("SHA-256", null, "sha256"),
    SHA512("SHA-512", null, "sha512"),
    C4("C4", "c4", "c4"),
    CRC32("CRC32", null, "crc32");

    fun create(): Hasher = when (this) {
        XXH64 -> XxhHasher(1)
        XXH3_64 -> XxhHasher(2)
        XXH3_128 -> XxhHasher(3)
        MD5 -> DigestHasher("MD5")
        SHA1 -> DigestHasher("SHA-1")
        SHA256 -> DigestHasher("SHA-256")
        SHA512 -> DigestHasher("SHA-512")
        C4 -> C4Hasher()
        CRC32 -> Crc32Hasher()
    }

    /** The text form of [digest]: lowercase hex, or the 90-character `c4...` identifier for C4. */
    fun format(digest: ByteArray): String = if (this == C4) C4Hasher.id(digest) else digest.joinToString("") { "%02x".format(it) }
}

/** Reference xxHash (XXH64 / XXH3-64 / XXH3-128), NEON-accelerated, from `libfeldnative`. */
object NativeXxh {
    init { System.loadLibrary("feldnative") }
    @JvmStatic external fun create(algo: Int): Long
    @JvmStatic external fun update(handle: Long, data: ByteArray, off: Int, len: Int)
    @JvmStatic external fun digest(handle: Long): ByteArray
    @JvmStatic external fun free(handle: Long)
}

class XxhHasher(algo: Int) : Hasher {
    private var handle = NativeXxh.create(algo)
    override fun update(data: ByteArray, off: Int, len: Int) { if (len > 0) NativeXxh.update(handle, data, off, len) }
    override fun digest(): ByteArray = NativeXxh.digest(handle)
    override fun release() { if (handle != 0L) { NativeXxh.free(handle); handle = 0L } }
}

class DigestHasher(alg: String) : Hasher {
    private val md = MessageDigest.getInstance(alg)
    override fun update(data: ByteArray, off: Int, len: Int) = md.update(data, off, len)
    override fun digest(): ByteArray = md.digest()
}

class Crc32Hasher : Hasher {
    private val crc = CRC32()
    override fun update(data: ByteArray, off: Int, len: Int) = crc.update(data, off, len)
    override fun digest(): ByteArray { val v = crc.value; return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()) }
}

/** C4 ID (Cinema Content Creation Cloud): SHA-512, written in base58 with a "c4" prefix, always 90 characters. */
class C4Hasher : Hasher {
    private val md = MessageDigest.getInstance("SHA-512")
    override fun update(data: ByteArray, off: Int, len: Int) = md.update(data, off, len)
    override fun digest(): ByteArray = md.digest()

    companion object {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        fun id(sha512: ByteArray): String {
            var n = BigInteger(1, sha512)
            val base = BigInteger.valueOf(58)
            val sb = StringBuilder()
            while (n.signum() > 0) { val (q, r) = n.divideAndRemainder(base); sb.append(ALPHABET[r.toInt()]); n = q }
            while (sb.length < 88) sb.append(ALPHABET[0])
            return "c4" + sb.reverse().toString()
        }
    }
}

/** Runs several hashes over the same bytes in one pass. */
class MultiHasher(private val algos: List<HashAlgo>) {
    private val hashers = algos.map { it.create() }
    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off) { for (h in hashers) h.update(data, off, len) }
    /** Digest per algorithm; the hashers are released afterwards. */
    fun finish(): Map<HashAlgo, ByteArray> {
        val out = LinkedHashMap<HashAlgo, ByteArray>()
        for ((i, a) in algos.withIndex()) { out[a] = hashers[i].digest(); hashers[i].release() }
        return out
    }
    fun finishHex(): Map<HashAlgo, String> = finish().mapValues { (a, d) -> a.format(d) }
}
