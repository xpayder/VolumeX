package app.feldkit.storage.filesystem.udf

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.VolumeInfo

/**
 * UDF (ECMA-167 / OSTA UDF 1.02 - 2.60): DVD and Blu-ray images, some camera and recorder media, and "UDF bridge" ISOs.
 * Plain and sparable partitions, short / long / embedded allocation descriptors, 8-bit and UTF-16 names. Read-only.
 * Virtual (CD-RW packet) partitions are not supported.
 */
class UdfReader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    companion object { private const val TAG = "FeldKit" }

    private var blockSize = 2048
    private var label = ""
    private val partStart = HashMap<Int, Long>()      // partition reference number -> first logical block (in blocks of blockSize)
    private var partLength = 0L
    private var rootIcb: LongAd? = null

    private class LongAd(val len: Long, val lbn: Long, val part: Int)
    private class Node(val dir: Boolean, val size: Long, val mtime: Long, val icb: LongAd)

    private val cache = HashMap<String, Node>()
    private val dirCache = HashMap<String, List<Pair<String, Node>>>()

    // ── byte access ──

    private fun readAbs(offset: Long, len: Int): ByteArray? {
        if (len <= 0) return ByteArray(0)
        val ss = dev.sectorSize()
        val abs = startLba * ss + offset
        val first = abs / ss; val last = (abs + len - 1) / ss
        val data = dev.readSectors(first, (last - first + 1).toInt()) ?: return null
        val skip = (abs - first * ss).toInt()
        return if (skip == 0 && data.size == len) data else data.copyOfRange(skip, skip + len)
    }

    private fun readBlocks(lbn: Long, part: Int, count: Int): ByteArray? {
        val base = partStart[part] ?: partStart.values.firstOrNull() ?: return null
        return readAbs((base + lbn) * blockSize, count * blockSize)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun dstring(b: ByteArray, o: Int, max: Int): String {
        if (max <= 1) return ""
        val len = (b[o + max - 1].toInt() and 0xFF).coerceAtMost(max - 1)
        if (len <= 1) return ""
        return decodeName(b, o, len)
    }

    private fun decodeName(b: ByteArray, o: Int, len: Int): String {
        if (len <= 0) return ""
        val comp = b[o].toInt() and 0xFF
        return when (comp) {
            8 -> String(b, o + 1, len - 1, Charsets.ISO_8859_1)
            16 -> String(b, o + 1, len - 1, Charsets.UTF_16BE)
            else -> String(b, o, len, Charsets.ISO_8859_1)
        }
    }

    // ── mount: anchor -> volume descriptor sequence -> file set descriptor ──

    override fun mount(): Boolean {
        // a UDF volume always has the BEA01/NSR02|03 recognition sequence at sector 16 onward (2048-byte blocks)
        var nsr = false
        for (s in 16..24) {
            val d = readAbs(s * 2048L, 8) ?: return false
            val id = String(d, 1, 5, Charsets.ISO_8859_1)
            if (id == "NSR02" || id == "NSR03") { nsr = true; break }
            if (id != "BEA01" && id != "BOOT2" && id != "CD001" && id != "TEA01") { if (s > 18) break }
        }
        if (!nsr) return false
        val total = dev.sectorCount() * dev.sectorSize()
        var avdp: ByteArray? = null
        for (cand in listOf(256L, if (total > 0) total / 2048 - 1 else -1L, if (total > 0) total / 2048 - 257 else -1L)) {
            if (cand <= 0) continue
            val d = readAbs(cand * 2048, 2048) ?: continue
            if (u16(d, 0) == 2) { avdp = d; break }
        }
        val a = avdp ?: return false
        val vdsLen = u32(a, 16); val vdsLoc = u32(a, 20)
        var lvd: ByteArray? = null
        val parts = ArrayList<ByteArray>()
        var s = vdsLoc
        val end = vdsLoc + (vdsLen / 2048).coerceAtLeast(1)
        while (s < end && s < vdsLoc + 64) {
            val d = readAbs(s * 2048, 2048) ?: break
            when (u16(d, 0)) {
                5 -> parts.add(d)
                6 -> lvd = d
                8 -> break
            }
            s++
        }
        val l = lvd ?: return false
        blockSize = u32(l, 212).toInt().coerceIn(512, 65536)
        label = dstring(l, 84, 128)
        // partition maps
        val nMaps = u32(l, 268).toInt()
        var mp = 440
        val mapParts = ArrayList<Int>()          // partition number per partition reference
        for (i in 0 until nMaps) {
            val type = l[mp].toInt() and 0xFF; val len = l[mp + 1].toInt() and 0xFF
            if (type == 1) mapParts.add(u16(l, mp + 4))
            else if (type == 2) {
                val id = String(l, mp + 5, 23, Charsets.ISO_8859_1)
                if (id.contains("Virtual")) return false.also { Log.w(TAG, "UDF: virtual partitions (CD-RW) are not supported") }
                mapParts.add(u16(l, mp + 38))    // sparable: the partition number sits after the sparing table info
            } else mapParts.add(-1)
            mp += len.coerceAtLeast(2)
        }
        for (pd in parts) {
            val number = u16(pd, 22)
            val start = u32(pd, 188); partLength = u32(pd, 192)
            // 'start' is in sectors of the medium (2048 here); convert to blocks of blockSize
            val startBytes = start * 2048L
            mapParts.forEachIndexed { ref, num -> if (num == number) partStart[ref] = startBytes / blockSize }
        }
        if (partStart.isEmpty()) return false
        val fsdLen = u32(l, 248); val fsdLbn = u32(l, 252); val fsdPart = u16(l, 256)
        val fsd = readBlocks(fsdLbn, fsdPart, 1) ?: return false
        if (u16(fsd, 0) != 256) return false
        rootIcb = LongAd(u32(fsd, 400), u32(fsd, 404), u16(fsd, 408))
        Log.i(TAG, "UDF: '$label' blockSize=$blockSize partitions=$partStart")
        return rootIcb != null
    }

    override fun getVolumeInfos(): List<VolumeInfo> = listOf(
        VolumeInfo(name = label.ifEmpty { "UDF volume" }, type = "UDF", totalBlocks = partLength, blockSize = blockSize.toLong(), freeKnown = true, freeBlocks = 0)
    )

    // ── file entries ──

    private class Extent(val kind: Int, val len: Long, val lbn: Long, val part: Int)   // kind: 0 recorded, 1 allocated-unrecorded, 2 unallocated

    private class Fe(val fileType: Int, val size: Long, val mtime: Long, val embedded: ByteArray?, val extents: List<Extent>)

    private fun timestamp(b: ByteArray, o: Int): Long = try {
        val year = u16(b, o + 2); val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(year, (b[o + 4].toInt() and 0xFF) - 1, b[o + 5].toInt() and 0xFF, b[o + 6].toInt() and 0xFF, b[o + 7].toInt() and 0xFF, b[o + 8].toInt() and 0xFF)
        }
        var tz = u16(b, o) and 0x0FFF; if (tz and 0x800 != 0) tz -= 0x1000
        c.timeInMillis - (if (tz in -1440..1440) tz else 0) * 60_000L
    } catch (_: Exception) { 0L }

    private fun readFe(icb: LongAd): Fe? {
        val blk = readBlocks(icb.lbn, icb.part, 1) ?: return null
        val tag = u16(blk, 0)
        if (tag != 261 && tag != 266) return null
        val ext = tag == 266
        val flags = u16(blk, 34); val fileType = blk[27].toInt() and 0xFF
        val size = u64(blk, 56)
        val mtime = timestamp(blk, 84)
        val lea = u32(blk, if (ext) 208 else 168).toInt(); val lad = u32(blk, if (ext) 212 else 172).toInt()
        val adStart = (if (ext) 216 else 176) + lea
        val type = flags and 7
        if (type == 3) {
            val full = if (adStart + lad <= blk.size) blk else readBlocks(icb.lbn, icb.part, (adStart + lad + blockSize - 1) / blockSize) ?: return null
            return Fe(fileType, size, mtime, full.copyOfRange(adStart, adStart + lad), emptyList())
        }
        val extents = ArrayList<Extent>()
        var buf = if (adStart + lad <= blk.size) blk else readBlocks(icb.lbn, icb.part, (adStart + lad + blockSize - 1) / blockSize) ?: return null
        var p = adStart; var end = adStart + lad
        var guard = 0
        while (p < end && guard++ < 100000) {
            val step = if (type == 0) 8 else 16
            if (p + step > buf.size) break
            val raw = u32(buf, p); val kind = (raw shr 30).toInt(); val len = raw and 0x3FFFFFFFL
            val lbn = u32(buf, p + 4); val part = if (type == 1) u16(buf, p + 8) else icb.part
            if (len == 0L) break
            if (kind == 3) {            // continuation: the next run of descriptors lives elsewhere
                val more = readBlocks(lbn, part, ((len + blockSize - 1) / blockSize).toInt()) ?: break
                buf = more; p = 0; end = len.toInt(); continue
            }
            extents.add(Extent(kind, len, lbn, part))
            p += step
        }
        return Fe(fileType, size, mtime, null, extents)
    }

    private fun readData(fe: Fe, offset: Long, len: Int): ByteArray? {
        val out = ByteArray(len)
        fe.embedded?.let { e ->
            val n = (minOf(fe.size, e.size.toLong()) - offset).coerceIn(0, len.toLong()).toInt()
            System.arraycopy(e, offset.toInt(), out, 0, n)
            return out.copyOf(n)
        }
        var skip = offset; var filled = 0
        for (x in fe.extents) {
            if (skip >= x.len) { skip -= x.len; continue }
            val n = minOf(x.len - skip, (len - filled).toLong()).toInt()
            if (x.kind == 0) {
                val base = partStart[x.part] ?: partStart.values.first()
                val b = readAbs((base + x.lbn) * blockSize + skip, n) ?: return if (filled > 0) out.copyOf(filled) else null
                System.arraycopy(b, 0, out, filled, n)
            }   // sparse extents stay zero
            filled += n; skip = 0
            if (filled >= len) break
        }
        return out.copyOf(filled)
    }

    private fun listNode(path: String): List<Pair<String, Node>>? {
        dirCache[path]?.let { return it }
        val node = if (path == "/" || path.isEmpty()) Node(true, 0, 0, rootIcb ?: return null) else (cache[path] ?: resolve(path) ?: return null)
        if (!node.dir) return null
        val fe = readFe(node.icb) ?: return null
        val data = readData(fe, 0, fe.size.coerceAtMost(32L shl 20).toInt()) ?: return null
        val out = ArrayList<Pair<String, Node>>()
        var p = 0
        while (p + 38 <= data.size) {
            if (u16(data, p) != 257) break
            val chars = data[p + 18].toInt() and 0xFF; val lfi = data[p + 19].toInt() and 0xFF
            val icb = LongAd(u32(data, p + 20), u32(data, p + 24), u16(data, p + 28))
            val liu = u16(data, p + 36)
            val nameOff = p + 38 + liu
            val total = (38 + liu + lfi + 3) / 4 * 4
            if (chars and 0x08 == 0 && chars and 0x04 == 0 && lfi > 0 && nameOff + lfi <= data.size) {
                val name = decodeName(data, nameOff, lfi)
                val child = readFe(icb)
                if (child != null) out.add(name to Node(chars and 0x02 != 0 || child.fileType == 4, child.size, child.mtime, icb))
            }
            p += total
        }
        dirCache[path] = out
        val base = if (path == "/") "" else path
        for ((n, nd) in out) cache["$base/$n"] = nd
        return out
    }

    private fun resolve(path: String): Node? {
        cache[path]?.let { return it }
        var cur = ""
        for (part in path.trim('/').split('/')) {
            val list = listNode(if (cur.isEmpty()) "/" else cur) ?: return null
            list.firstOrNull { it.first == part } ?: return null
            cur = "$cur/$part"
        }
        return cache[path]
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val base = if (path == "/" || path.isEmpty()) "" else path.trimEnd('/')
        val list = listNode(if (base.isEmpty()) "/" else base) ?: return emptyList()
        return list.map { (n, nd) -> FileSystemEntry(n, "$base/$n", nd.dir, if (nd.dir) 0 else nd.size, nd.mtime, nd.mtime) }
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        return if (readFileTo(entry, out)) out.toByteArray() else null
    }

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val node = resolve(entry.path) ?: return false
        val fe = readFe(node.icb) ?: return false
        var off = 0L
        while (off < fe.size) {
            val n = minOf(1L shl 20, fe.size - off).toInt()
            val b = readData(fe, off, n) ?: return false
            if (b.isEmpty()) break
            out.write(b); off += b.size; onProgress?.invoke(off)
        }
        return true
    }

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        val node = resolve(entry.path) ?: return -1
        val fe = readFe(node.icb) ?: return -1
        if (offset >= fe.size) return 0
        val b = readData(fe, offset, minOf(len.toLong(), fe.size - offset).toInt()) ?: return -1
        System.arraycopy(b, 0, buf, bufOff, b.size)
        return b.size
    }

    override fun unmount() { cache.clear(); dirCache.clear() }
}
