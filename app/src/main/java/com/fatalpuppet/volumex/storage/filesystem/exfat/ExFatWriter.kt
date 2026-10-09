package com.fatalpuppet.volumex.storage.filesystem.exfat

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import java.time.Instant
import java.time.ZoneOffset

/**
 * exFAT writer. Follows the exFAT spec: every allocation is recorded in the allocation bitmap and
 * (for files/directories written here) in the FAT; directory entry sets carry the SetChecksum and
 * NameHash. Order of writes is data -> FAT -> bitmap -> directory entry, so an interrupted write
 * leaves at worst orphaned (but harmless) allocated clusters, never a dangling directory entry.
 */
class ExFatWriter(
    private val dev: BlockDeviceReader,
    private val reader: ExFatReader
) : FileSystemWriter {

    companion object {
        private const val TAG = "VolumeX"
        private const val EOC = 0xFFFFFFFFL
        private const val MAX_BITMAP_BYTES = 64 * 1024 * 1024
    }

    private val b get() = reader.getBootSector()!!
    private val csize get() = b.bytesPerCluster
    private val bps get() = b.bytesPerSector

    // ── Volume metadata (bitmap, upcase table) ────────────────────────────────

    private var bitmap: ByteArray? = null
    private var bitmapLba = 0L
    private val bitmapDirty = sortedSetOf<Int>()   // dirty sector indexes within the bitmap
    private var upcase: CharArray? = null
    private var allocHint = 2L

    private fun init(): Boolean {
        if (bitmap != null) return true
        val root = readDir(rootDir())
        var i = 0
        while (i + 32 <= root.size) {
            val t = root[i].toInt() and 0xFF
            if (t == 0) break
            val first = u32(root, i + 20)
            val len = u64(root, i + 24)
            if (t == 0x81 && bitmap == null) {
                if (len > MAX_BITMAP_BYTES) return false
                bitmapLba = clusterLba(first)
                val sectors = ((len + bps - 1) / bps).toInt()
                bitmap = dev.readSectors(bitmapLba, sectors)?.copyOf(len.toInt()) ?: return false
            } else if (t == 0x82 && upcase == null) {
                val sectors = ((len + bps - 1) / bps).toInt()
                val raw = dev.readSectors(clusterLba(first), sectors) ?: return false
                upcase = loadUpcase(raw, len.toInt())
            }
            i += 32
        }
        if (upcase == null) upcase = CharArray(0)
        return bitmap != null
    }

    /** Expand the (possibly run-length compressed) upcase table into a direct lookup array. */
    private fun loadUpcase(raw: ByteArray, len: Int): CharArray {
        val out = ArrayList<Char>()
        var i = 0
        while (i + 1 < len) {
            val v = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
            if (v == 0xFFFF && i + 3 < len) {
                val n = (raw[i + 2].toInt() and 0xFF) or ((raw[i + 3].toInt() and 0xFF) shl 8)
                repeat(n) { out.add(out.size.toChar()) }
                i += 4
            } else { out.add(v.toChar()); i += 2 }
        }
        return out.toCharArray()
    }

    private fun up(c: Char): Char {
        val t = upcase ?: return c
        return if (c.code < t.size) t[c.code] else c
    }

    private fun nameEq(a: String, bb: String): Boolean {
        if (a.length != bb.length) return false
        for (k in a.indices) if (up(a[k]) != up(bb[k])) return false
        return true
    }

    private fun nameHash(name: String): Int {
        var h = 0
        for (c in name) {
            val u = up(c).code
            for (byte in intArrayOf(u and 0xFF, (u shr 8) and 0xFF)) {
                h = (((h and 1) shl 15) or (h ushr 1)) + byte
                h = h and 0xFFFF
            }
        }
        return h
    }

    private fun setChecksum(set: ByteArray, off: Int, count: Int): Int {
        var sum = 0
        for (k in 0 until count * 32) {
            if (k == 2 || k == 3) continue
            sum = (((sum and 1) shl 15) or (sum ushr 1)) + (set[off + k].toInt() and 0xFF)
            sum = sum and 0xFFFF
        }
        return sum
    }

    // ── Bitmap / FAT ──────────────────────────────────────────────────────────

    private fun bitGet(c: Long): Boolean {
        val bm = bitmap!!; val idx = c - 2
        return (bm[(idx shr 3).toInt()].toInt() shr (idx and 7).toInt()) and 1 != 0
    }

    private fun bitSet(c: Long, v: Boolean) {
        val bm = bitmap!!; val idx = c - 2; val bi = (idx shr 3).toInt()
        val mask = 1 shl (idx and 7).toInt()
        bm[bi] = (if (v) bm[bi].toInt() or mask else bm[bi].toInt() and mask.inv()).toByte()
        bitmapDirty.add(bi / bps)
    }

    private fun flushBitmap() {
        val bm = bitmap ?: return
        for (si in bitmapDirty) {
            val sec = ByteArray(bps)
            System.arraycopy(bm, si * bps, sec, 0, minOf(bps, bm.size - si * bps))
            dev.writeSector(bitmapLba + si, sec)
        }
        bitmapDirty.clear()
    }

    private fun allocate(n: Int): List<Long>? {
        if (n <= 0) return emptyList()
        val total = b.clusterCount
        // first fit for a contiguous run, starting at the hint, wrapping once
        for (pass in 0..1) {
            var c = if (pass == 0) allocHint else 2L
            val end = if (pass == 0) total + 2 else minOf(allocHint + n, total + 2)
            var runStart = -1L; var run = 0
            while (c < end) {
                if (!bitGet(c)) {
                    if (run == 0) runStart = c
                    run++
                    if (run == n) {
                        val list = (0 until n).map { runStart + it }
                        list.forEach { bitSet(it, true) }
                        allocHint = runStart + n
                        return list
                    }
                } else run = 0
                c++
            }
        }
        // fragmented: take any free clusters
        val list = ArrayList<Long>(n)
        var c = 2L
        while (c < total + 2 && list.size < n) { if (!bitGet(c)) list.add(c); c++ }
        if (list.size < n) return null
        list.forEach { bitSet(it, true) }
        return list
    }

    private fun fatSetMany(entries: Map<Long, Long>) {
        val bySector = entries.entries.groupBy { (it.key * 4) / bps }
        for ((sec, list) in bySector) {
            for (fat in 0 until b.numberOfFats) {
                val lba = b.partitionStartLba + b.fatOffset + fat * b.fatLength + sec
                val buf = dev.readSector(lba)?.clone() ?: ByteArray(bps)
                for ((c, v) in list) {
                    val o = ((c * 4) % bps).toInt()
                    buf[o] = v.toByte(); buf[o + 1] = (v shr 8).toByte(); buf[o + 2] = (v shr 16).toByte(); buf[o + 3] = (v shr 24).toByte()
                }
                dev.writeSector(lba, buf)
            }
        }
    }

    private fun chainOf(clusters: List<Long>): Map<Long, Long> {
        val m = LinkedHashMap<Long, Long>()
        for (i in clusters.indices) m[clusters[i]] = if (i + 1 < clusters.size) clusters[i + 1] else EOC
        return m
    }

    // ── Cluster I/O ───────────────────────────────────────────────────────────

    private fun clusterLba(c: Long) = b.partitionStartLba + b.clusterHeapOffset + (c - 2) * b.sectorsPerCluster

    private fun writeClusters(clusters: List<Long>, data: ByteArray, dataLen: Int = data.size) {
        var i = 0
        while (i < clusters.size) {
            var j = i
            while (j + 1 < clusters.size && clusters[j + 1] == clusters[j] + 1) j++
            val run = j - i + 1
            val buf = ByteArray(run * csize)
            val from = i * csize
            val n = minOf(buf.size, dataLen - from).coerceAtLeast(0)
            if (n > 0) System.arraycopy(data, from, buf, 0, n)
            dev.writeSectors(clusterLba(clusters[i]), buf)
            i = j + 1
        }
    }

    // ── Directory model ───────────────────────────────────────────────────────

    private class Dir(var first: Long, var contiguous: Boolean, var length: Long, val parent: Dir?, val setOffset: Int)

    private fun rootDir() = Dir(b.rootDirectoryCluster, false, -1L, null, -1)

    private fun clustersOf(d: Dir): List<Long> {
        if (d.contiguous) {
            val n = ((d.length + csize - 1) / csize).toInt()
            return (0 until n).map { d.first + it }
        }
        val out = ArrayList<Long>()
        var c = d.first; var guard = 0
        while (c >= 2 && c < 0xFFFFFFF8L && guard++ < 50_000_000) { out.add(c); c = reader.readFatEntry(c) }
        return out
    }

    private fun readDir(d: Dir): ByteArray {
        val cl = clustersOf(d)
        val out = ByteArray(cl.size * csize)
        var i = 0
        while (i < cl.size) {
            var j = i
            while (j + 1 < cl.size && cl[j + 1] == cl[j] + 1) j++
            val chunk = dev.readSectors(clusterLba(cl[i]), (j - i + 1) * b.sectorsPerCluster) ?: return out
            System.arraycopy(chunk, 0, out, i * csize, chunk.size)
            i = j + 1
        }
        return out
    }

    private fun writeDir(d: Dir, data: ByteArray) = writeClusters(clustersOf(d), data)

    private class SetInfo(val off: Int, val count: Int, val name: String, val attr: Int, val flags: Int, val first: Long, val valid: Long, val len: Long)

    private fun scan(data: ByteArray): List<SetInfo> {
        val out = ArrayList<SetInfo>()
        var i = 0
        while (i + 32 <= data.size) {
            val t = data[i].toInt() and 0xFF
            if (t == 0) break
            if (t == 0x85) {
                val sec = data[i + 1].toInt() and 0xFF
                if (i + 32 * (sec + 1) > data.size) break
                val s = i + 32
                if ((data[s].toInt() and 0xFF) == 0xC0) {
                    val nl = data[s + 3].toInt() and 0xFF
                    val sb = StringBuilder()
                    var k = 2
                    while (k <= sec && sb.length < nl) {
                        val e = i + k * 32
                        if ((data[e].toInt() and 0xFF) == 0xC1) {
                            for (q in 0 until 15) {
                                if (sb.length >= nl) break
                                sb.append((((data[e + 3 + q * 2].toInt() and 0xFF) shl 8) or (data[e + 2 + q * 2].toInt() and 0xFF)).toChar())
                            }
                        }
                        k++
                    }
                    out.add(SetInfo(i, sec + 1, sb.toString(), (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8),
                        data[s + 1].toInt() and 0xFF, u32(data, s + 20), u64(data, s + 8), u64(data, s + 24)))
                }
                i += 32 * (sec + 1)
            } else i += 32
        }
        return out
    }

    private fun dirForPath(path: String): Dir? {
        var cur = rootDir()
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            val s = scan(readDir(cur)).firstOrNull { nameEq(it.name, part) && (it.attr and 0x10) != 0 } ?: return null
            cur = Dir(s.first, (s.flags and 0x02) != 0, s.len, cur, s.off)
        }
        return cur
    }

    private fun parentDir(entry: FileSystemEntry): Dir? =
        dirForPath(entry.path.substringBeforeLast('/', ""))

    private fun dirOf(parentEntry: FileSystemEntry): Dir? =
        if (parentEntry.path == "/" || parentEntry.path.isEmpty()) rootDir() else dirForPath(parentEntry.path)

    private fun updateStream(parent: Dir, setOffset: Int, d: Dir) {
        val data = readDir(parent)
        val s = setOffset + 32
        data[s + 1] = (0x01 or (if (d.contiguous) 0x02 else 0)).toByte()
        put64(data, s + 8, d.length); put64(data, s + 24, d.length); put32(data, s + 20, d.first)
        val cnt = data[setOffset + 1].toInt() and 0xFF
        val sum = setChecksum(data, setOffset, cnt + 1)
        data[setOffset + 2] = sum.toByte(); data[setOffset + 3] = (sum shr 8).toByte()
        writeDir(parent, data)
    }

    /** Add one zeroed cluster to a directory; keeps parent stream entry in sync. */
    private fun growDir(d: Dir): Boolean {
        val last = clustersOf(d).lastOrNull() ?: return false
        val c = allocate(1)?.first() ?: return false
        writeClusters(listOf(c), ByteArray(csize))
        if (d.contiguous && c != last + 1) {
            // cannot stay contiguous: switch to a FAT chain covering the existing clusters
            fatSetMany(chainOf(clustersOf(d)).toMutableMap().also { it[last] = c })
            d.contiguous = false
        } else if (!d.contiguous) {
            fatSetMany(mapOf(last to c))
        }
        fatSetMany(mapOf(c to EOC))
        flushBitmap()
        if (d.length >= 0) d.length += csize
        d.parent?.let { updateStream(it, d.setOffset, d) }
        return true
    }

    private class Buf(val dir: Dir, var data: ByteArray)

    /** Insert an entry set into the buffer, growing the directory if needed. Returns the set offset. */
    private fun insert(buf: Buf, set: ByteArray): Int? {
        val need = set.size / 32
        var i = 0; var runStart = -1; var run = 0; var end = -1
        while (i + 32 <= buf.data.size) {
            val t = buf.data[i].toInt() and 0xFF
            if (t == 0) { end = i; break }
            if (t and 0x80 == 0) { if (run == 0) runStart = i; run++; if (run >= need) { place(buf, runStart, set); return runStart } }
            else { run = 0; runStart = -1 }
            i += 32
        }
        var at = if (end >= 0) end else buf.data.size
        if (run > 0 && end >= 0) at = runStart   // deleted run continues into the unused tail
        while (at + set.size > buf.data.size) {
            if (!growDir(buf.dir)) return null
            val grown = readDir(buf.dir)
            buf.data = grown
        }
        place(buf, at, set)
        return at
    }

    private fun place(buf: Buf, at: Int, set: ByteArray) { System.arraycopy(set, 0, buf.data, at, set.size) }

    // ── Entry set construction ────────────────────────────────────────────────

    private fun dosTs(ms: Long): Long {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        val y = (t.year - 1980).coerceIn(0, 127)
        return ((y.toLong() shl 25) or (t.monthValue.toLong() shl 21) or (t.dayOfMonth.toLong() shl 16) or
            (t.hour.toLong() shl 11) or (t.minute.toLong() shl 5) or (t.second / 2).toLong())
    }

    private fun buildSet(name: String, attr: Int, first: Long, valid: Long, len: Long, flags: Int, nowMs: Long): ByteArray {
        val nameExt = (name.length + 14) / 15
        val count = 1 + nameExt                      // secondary entries
        val set = ByteArray((count + 1) * 32)
        set[0] = 0x85.toByte(); set[1] = count.toByte()
        set[4] = attr.toByte(); set[5] = (attr shr 8).toByte()
        val ts = dosTs(nowMs)
        put32(set, 8, ts); put32(set, 12, ts); put32(set, 16, ts)
        set[22] = 0x80.toByte(); set[23] = 0x80.toByte(); set[24] = 0x80.toByte()   // UTC, offset valid
        val s = 32
        set[s] = 0xC0.toByte(); set[s + 1] = flags.toByte(); set[s + 3] = name.length.toByte()
        val h = nameHash(name); set[s + 4] = h.toByte(); set[s + 5] = (h shr 8).toByte()
        put64(set, s + 8, valid); put32(set, s + 20, first); put64(set, s + 24, len)
        for (e in 0 until nameExt) {
            val o = 64 + e * 32
            set[o] = 0xC1.toByte()
            for (q in 0 until 15) {
                val idx = e * 15 + q
                val ch = if (idx < name.length) name[idx].code else 0
                set[o + 2 + q * 2] = ch.toByte(); set[o + 3 + q * 2] = (ch shr 8).toByte()
            }
        }
        val sum = setChecksum(set, 0, count + 1)
        set[2] = sum.toByte(); set[3] = (sum shr 8).toByte()
        return set
    }

    private fun validName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255 || name == "." || name == "..") return false
        if (name.endsWith(" ") || name.endsWith(".")) return false
        return name.none { it.code < 0x20 || it in "\"*/:<>?\\|" }
    }

    // ── FileSystemWriter ──────────────────────────────────────────────────────

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean = guarded("writeFile $name") {
        if (!init() || !validName(name)) return@guarded false
        val dir = dirOf(parentEntry) ?: return@guarded false
        val buf = Buf(dir, readDir(dir))
        if (scan(buf.data).any { nameEq(it.name, name) }) { Log.w(TAG, "exFAT: '$name' already exists"); return@guarded false }

        val n = ((data.size + csize - 1) / csize)
        val clusters = allocate(n) ?: run { Log.e(TAG, "exFAT: no space for $name"); return@guarded false }
        writeClusters(clusters, data)
        if (clusters.isNotEmpty()) fatSetMany(chainOf(clusters))
        flushBitmap()
        val set = buildSet(name, 0x20, clusters.firstOrNull() ?: 0L, data.size.toLong(), data.size.toLong(), 0x01, System.currentTimeMillis())
        insert(buf, set) ?: return@guarded false
        writeDir(buf.dir, buf.data)
        dev.flushCache()
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean = guarded("createDirectory $name") {
        if (!init() || !validName(name)) return@guarded false
        val dir = dirOf(parentEntry) ?: return@guarded false
        val buf = Buf(dir, readDir(dir))
        if (scan(buf.data).any { nameEq(it.name, name) }) return@guarded false
        val c = allocate(1)?.first() ?: return@guarded false
        writeClusters(listOf(c), ByteArray(csize))
        fatSetMany(mapOf(c to EOC))
        flushBitmap()
        val set = buildSet(name, 0x10, c, csize.toLong(), csize.toLong(), 0x01, System.currentTimeMillis())
        insert(buf, set) ?: return@guarded false
        writeDir(buf.dir, buf.data)
        dev.flushCache()
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean = guarded("deleteEntry ${entry.name}") {
        if (!init()) return@guarded false
        val dir = parentDir(entry) ?: return@guarded false
        val data = readDir(dir)
        val s = scan(data).firstOrNull { nameEq(it.name, entry.name) } ?: return@guarded false
        deleteSet(dir, data, s)
        writeDir(dir, data)
        flushBitmap()
        dev.flushCache()
    }

    private fun deleteSet(dir: Dir, data: ByteArray, s: SetInfo) {
        if ((s.attr and 0x10) != 0 && s.first >= 2) {
            val child = Dir(s.first, (s.flags and 0x02) != 0, s.len, dir, s.off)
            val cdata = readDir(child)
            for (c in scan(cdata)) deleteSet(child, cdata, c)
            writeDir(child, cdata)
        }
        if (s.first >= 2) freeStorage(Dir(s.first, (s.flags and 0x02) != 0, s.len, dir, s.off))
        for (k in 0 until s.count) data[s.off + k * 32] = (data[s.off + k * 32].toInt() and 0x7F).toByte()
    }

    private fun freeStorage(d: Dir) {
        val cl = clustersOf(d)
        cl.forEach { bitSet(it, false) }
        if (!d.contiguous) fatSetMany(cl.associateWith { 0L })
        if (cl.isNotEmpty() && cl.first() < allocHint) allocHint = cl.first()
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean = guarded("renameEntry ${entry.name}") {
        if (!init() || !validName(newName)) return@guarded false
        val dir = parentDir(entry) ?: return@guarded false
        val buf = Buf(dir, readDir(dir))
        val s = scan(buf.data).firstOrNull { nameEq(it.name, entry.name) } ?: return@guarded false
        if (!nameEq(entry.name, newName) && scan(buf.data).any { nameEq(it.name, newName) }) return@guarded false
        val old = buf.data.copyOfRange(s.off, s.off + s.count * 32)
        val fresh = buildSet(newName, s.attr, s.first, s.valid, s.len, s.flags, System.currentTimeMillis())
        System.arraycopy(old, 8, fresh, 8, 16)            // keep original timestamps
        val sum = setChecksum(fresh, 0, fresh.size / 32)
        fresh[2] = sum.toByte(); fresh[3] = (sum shr 8).toByte()
        for (k in 0 until s.count) buf.data[s.off + k * 32] = (buf.data[s.off + k * 32].toInt() and 0x7F).toByte()
        insert(buf, fresh) ?: return@guarded false
        writeDir(buf.dir, buf.data)
        dev.flushCache()
    }

    private inline fun guarded(what: String, block: () -> Boolean): Boolean = try {
        block().also { if (!it) Log.w(TAG, "exFAT $what failed") }
    } catch (e: Exception) { Log.e(TAG, "exFAT $what threw", e); false }

    // ── little-endian helpers ─────────────────────────────────────────────────
    private fun u32(a: ByteArray, o: Int) = ((a[o].toLong() and 0xFF) or ((a[o + 1].toLong() and 0xFF) shl 8) or ((a[o + 2].toLong() and 0xFF) shl 16) or ((a[o + 3].toLong() and 0xFF) shl 24))
    private fun u64(a: ByteArray, o: Int) = u32(a, o) or (u32(a, o + 4) shl 32)
    private fun put32(a: ByteArray, o: Int, v: Long) { for (k in 0..3) a[o + k] = (v shr (8 * k)).toByte() }
    private fun put64(a: ByteArray, o: Int, v: Long) { for (k in 0..7) a[o + k] = (v shr (8 * k)).toByte() }
}
