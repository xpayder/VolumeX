package com.fatalpuppet.volumex.storage.filesystem.fat32

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import java.util.Calendar

/**
 * FAT32 writer: FAT chains on every FAT copy, FSInfo free-count maintenance, long file names with
 * the short-name checksum, unique "~N" short aliases, and '.'/'..' entries for new directories.
 * Write order is data -> FAT -> directory entry.
 */
class Fat32Writer(
    private val dev: BlockDeviceReader,
    private val header: Fat32VolumeHeader
) : FileSystemWriter {

    companion object {
        private const val TAG = "VolumeX"
        private const val EOC = 0x0FFFFFFFL
        private const val MASK = 0x0FFFFFFFL
        private const val ATTR_DIR = 0x10
        private const val ATTR_ARCHIVE = 0x20
        private const val ATTR_LFN = 0x0F
    }

    private val bps = header.bytesPerSector
    private val spc = header.sectorsPerCluster
    private val csize = header.clusterSize
    private val totalClusters = ((header.totalSectors32 - (header.dataAreaLba - header.partitionStartLba)) / spc)
    private var hint = 2L
    private var freeDelta = 0L
    private val fatCache = HashMap<Long, ByteArray>()

    // ── FAT access (write-through to all copies) ──────────────────────────────

    private fun fatSector(sec: Long): ByteArray = fatCache.getOrPut(sec) {
        dev.readSector(header.fatStartLba + sec)?.clone() ?: ByteArray(bps)
    }

    private fun fatGet(c: Long): Long {
        val s = fatSector((c * 4) / bps); val o = ((c * 4) % bps).toInt()
        return ((s[o].toLong() and 0xFF) or ((s[o + 1].toLong() and 0xFF) shl 8) or ((s[o + 2].toLong() and 0xFF) shl 16) or ((s[o + 3].toLong() and 0xFF) shl 24)) and MASK
    }

    private val dirtyFat = sortedSetOf<Long>()
    private fun fatPut(c: Long, v: Long) {
        val sec = (c * 4) / bps; val s = fatSector(sec); val o = ((c * 4) % bps).toInt()
        val old = (s[o + 3].toLong() and 0xF0) shl 24     // preserve the reserved top nibble
        val nv = (v and MASK) or old
        s[o] = nv.toByte(); s[o + 1] = (nv shr 8).toByte(); s[o + 2] = (nv shr 16).toByte(); s[o + 3] = (nv shr 24).toByte()
        dirtyFat.add(sec)
    }

    private fun flushFat() {
        // group consecutive dirty FAT sectors into single multi-sector writes
        val secs = dirtyFat.toList()
        var i = 0
        while (i < secs.size) {
            var j = i
            while (j + 1 < secs.size && secs[j + 1] == secs[j] + 1 && j - i < 255) j++
            val run = ByteArray((j - i + 1) * bps)
            for (k in i..j) System.arraycopy(fatCache[secs[k]]!!, 0, run, (k - i) * bps, bps)
            for (f in 0 until header.fatCount) dev.writeSectors(header.fatStartLba + f * header.fatSize32 + secs[i], run)
            i = j + 1
        }
        dirtyFat.clear()
        updateFsInfo()
    }

    private fun updateFsInfo() {
        if (freeDelta == 0L && hint == 2L) return
        val boot = dev.readSector(header.partitionStartLba) ?: return
        val fsInfoSec = (boot[48].toInt() and 0xFF) or ((boot[49].toInt() and 0xFF) shl 8)
        if (fsInfoSec == 0 || fsInfoSec == 0xFFFF) return
        val lba = header.partitionStartLba + fsInfoSec
        val s = dev.readSector(lba)?.clone() ?: return
        if (le32(s, 0) != 0x41615252L || le32(s, 484) != 0x61417272L) return
        val free = le32(s, 488)
        if (free != 0xFFFFFFFFL) putLe32(s, 488, (free + freeDelta).coerceIn(0, totalClusters))
        putLe32(s, 492, hint)
        dev.writeSector(lba, s)
        freeDelta = 0
    }

    private fun allocate(n: Int): List<Long>? {
        val out = ArrayList<Long>(n)
        var c = hint
        var wrapped = false
        while (out.size < n) {
            if (c >= totalClusters + 2) { if (wrapped) break; wrapped = true; c = 2 }
            if (fatGet(c) == 0L && !out.contains(c)) out.add(c)
            c++
            if (wrapped && c >= hint) break
        }
        if (out.size < n) return null
        for (i in out.indices) fatPut(out[i], if (i + 1 < out.size) out[i + 1] else EOC)
        hint = out.last() + 1
        freeDelta -= n
        return out
    }

    private fun freeChain(first: Long) {
        var c = first; var guard = 0
        while (c >= 2 && c < 0x0FFFFFF8L && guard++ < 0x0FFFFFF0) {
            val nx = fatGet(c); fatPut(c, 0); freeDelta++
            if (c < hint) hint = c
            c = nx
        }
    }

    // ── Cluster I/O ───────────────────────────────────────────────────────────

    private fun lba(c: Long) = header.dataAreaLba + (c - 2) * spc

    private fun chain(first: Long): List<Long> {
        val out = ArrayList<Long>(); var c = first; var guard = 0
        while (c >= 2 && c < 0x0FFFFFF8L && guard++ < 0x0FFFFFF0) { out.add(c); c = fatGet(c) }
        return out
    }

    private fun readClusters(cl: List<Long>): ByteArray {
        val out = ByteArray(cl.size * csize)
        var i = 0
        while (i < cl.size) {
            var j = i
            while (j + 1 < cl.size && cl[j + 1] == cl[j] + 1) j++
            val chunk = dev.readSectors(lba(cl[i]), (j - i + 1) * spc) ?: return out
            System.arraycopy(chunk, 0, out, i * csize, chunk.size)
            i = j + 1
        }
        return out
    }

    private fun writeClusters(cl: List<Long>, data: ByteArray) {
        var i = 0
        while (i < cl.size) {
            var j = i
            while (j + 1 < cl.size && cl[j + 1] == cl[j] + 1) j++
            val buf = ByteArray((j - i + 1) * csize)
            val from = i * csize
            val n = minOf(buf.size, data.size - from).coerceAtLeast(0)
            if (n > 0) System.arraycopy(data, from, buf, 0, n)
            dev.writeSectors(lba(cl[i]), buf)
            i = j + 1
        }
    }

    // ── Directories ───────────────────────────────────────────────────────────

    private class Item(val off: Int, val count: Int, val name: String, val shortName: String, val attr: Int, val first: Long, val size: Long)

    private fun scan(data: ByteArray): List<Item> {
        val out = ArrayList<Item>()
        var i = 0; var lfnStart = -1; val parts = ArrayList<String>()
        while (i + 32 <= data.size) {
            val f = data[i].toInt() and 0xFF
            if (f == 0) break
            if (f == 0xE5) { i += 32; lfnStart = -1; parts.clear(); continue }
            val attr = data[i + 11].toInt() and 0xFF
            if (attr == ATTR_LFN) {
                if (lfnStart < 0) lfnStart = i
                parts.add(0, lfnChars(data, i)); i += 32; continue
            }
            if (attr and 0x08 != 0 && attr and ATTR_DIR == 0) { i += 32; lfnStart = -1; parts.clear(); continue }
            val short = shortOf(data, i)
            val name = if (parts.isNotEmpty()) parts.joinToString("") else short
            val first = (le16(data, i + 20) shl 16) or le16(data, i + 26)
            out.add(Item(if (lfnStart >= 0) lfnStart else i, (i - (if (lfnStart >= 0) lfnStart else i)) / 32 + 1, name, short, attr, first, le32(data, i + 28)))
            lfnStart = -1; parts.clear(); i += 32
        }
        return out
    }

    private fun shortOf(d: ByteArray, o: Int): String {
        var base = String(d, o, 8, Charsets.ISO_8859_1).trimEnd()
        var ext = String(d, o + 8, 3, Charsets.ISO_8859_1).trimEnd()
        val nt = d[o + 12].toInt()
        if (nt and 0x08 != 0) base = base.lowercase()
        if (nt and 0x10 != 0) ext = ext.lowercase()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun lfnChars(d: ByteArray, o: Int): String {
        val sb = StringBuilder()
        for (p in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
            val ch = (d[o + p].toInt() and 0xFF) or ((d[o + p + 1].toInt() and 0xFF) shl 8)
            if (ch == 0 || ch == 0xFFFF) break
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private class Dir(val first: Long, val parentFirst: Long)

    private fun rootDir() = Dir(header.rootCluster, 0)

    private fun dirForPath(path: String): Dir? {
        var cur = rootDir()
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            val it = scan(readClusters(chain(cur.first))).firstOrNull { x -> x.name.equals(part, true) && x.attr and ATTR_DIR != 0 } ?: return null
            cur = Dir(it.first, if (cur.first == header.rootCluster) 0 else cur.first)
        }
        return cur
    }

    private fun dirOf(e: FileSystemEntry) = if (e.path == "/" || e.path.isEmpty()) rootDir() else dirForPath(e.path)

    // ── Names ─────────────────────────────────────────────────────────────────

    private val illegalShort = "\"*+,/:;<=>?[\\]|"

    private fun toShortParts(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        var base = if (dot > 0) name.substring(0, dot) else name
        var ext = if (dot > 0) name.substring(dot + 1) else ""
        fun clean(s: String) = s.uppercase().filter { it != ' ' && it != '.' && it.code in 0x21..0x7E && it !in illegalShort }.ifEmpty { "_" }
        base = clean(base); ext = if (ext.isEmpty()) "" else clean(ext)
        return base to ext.take(3)
    }

    /** True when [name] is exactly representable as an upper-case 8.3 name (no LFN needed). */
    private fun fitsShort(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        fun ok(s: String) = s.all { it.code in 0x21..0x7E && it !in illegalShort && !it.isLowerCase() && it != '.' }
        return name.indexOf('.') == dot && base.length in 1..8 && ext.length <= 3 && ok(base) && ok(ext) && name != "." && name != ".."
    }

    private fun uniqueShort(name: String, existing: Set<String>): String {
        val (base, ext) = toShortParts(name)
        fun pack(b: String, e: String) = b.padEnd(8) + e.padEnd(3)
        if (fitsShort(name)) return pack(base, ext)
        var n = 1
        while (true) {
            val tail = "~$n"
            val cand = pack(base.take(8 - tail.length) + tail, ext)
            if (cand !in existing) return cand
            n++
        }
    }

    private fun checksum(short11: ByteArray): Int {
        var s = 0
        for (b in short11) s = (((s and 1) shl 7) or ((s and 0xFF) ushr 1)) + (b.toInt() and 0xFF) and 0xFF
        return s
    }

    private fun buildEntries(name: String, short11: String, attr: Int, first: Long, size: Long, ms: Long, createMs: Long = ms): ByteArray {
        val shortBytes = short11.toByteArray(Charsets.ISO_8859_1)
        val needLfn = name != "." && name != ".." && !fitsShort(name)
        val lfnCount = if (needLfn) (name.length + 12) / 13 else 0
        val out = ByteArray((lfnCount + 1) * 32)
        val cs = checksum(shortBytes)
        for (k in 0 until lfnCount) {            // entry order on disk: highest sequence first
            val seq = lfnCount - k
            val o = k * 32
            out[o] = (seq or (if (seq == lfnCount) 0x40 else 0)).toByte()
            out[o + 11] = ATTR_LFN.toByte(); out[o + 13] = cs.toByte()
            val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for ((q, p) in positions.withIndex()) {
                val idx = (seq - 1) * 13 + q
                val ch = when { idx < name.length -> name[idx].code; idx == name.length -> 0; else -> 0xFFFF }
                out[o + p] = ch.toByte(); out[o + p + 1] = (ch shr 8).toByte()
            }
        }
        val o = lfnCount * 32
        System.arraycopy(shortBytes, 0, out, o, 11)
        out[o + 11] = attr.toByte()
        val (cd, ct) = fatDate(createMs); val (wd, wt) = fatDate(ms)
        putLe16(out, o + 14, ct); putLe16(out, o + 16, cd); putLe16(out, o + 18, wd)
        putLe16(out, o + 20, (first shr 16).toInt()); putLe16(out, o + 22, wt); putLe16(out, o + 24, wd)
        putLe16(out, o + 26, (first and 0xFFFF).toInt()); putLe32(out, o + 28, size)
        return out
    }

    private fun fatDate(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val d = ((c.get(Calendar.YEAR) - 1980).coerceIn(0, 127) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        val t = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        return d to t
    }

    private fun validName(name: String) = name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." &&
        !name.endsWith(" ") && !name.endsWith(".") && name.none { it.code < 0x20 || it in "\"*/:<>?\\|" }

    // ── Insert ────────────────────────────────────────────────────────────────

    private class Buf(val first: Long, var clusters: List<Long>, var data: ByteArray)

    private fun open(d: Dir): Buf { val cl = chain(d.first); return Buf(d.first, cl, readClusters(cl)) }

    private fun insert(buf: Buf, entries: ByteArray): Boolean {
        val need = entries.size / 32
        var i = 0; var runStart = -1; var run = 0; var end = -1
        while (i + 32 <= buf.data.size) {
            val f = buf.data[i].toInt() and 0xFF
            if (f == 0) { end = i; break }
            if (f == 0xE5) { if (run == 0) runStart = i; run++; if (run >= need) { System.arraycopy(entries, 0, buf.data, runStart, entries.size); return true } }
            else { run = 0; runStart = -1 }
            i += 32
        }
        var at = if (end >= 0) end else buf.data.size
        if (run > 0 && end >= 0) at = runStart
        while (at + entries.size > buf.data.size) {
            val c = allocate(1) ?: return false
            fatPut(buf.clusters.last(), c[0])
            writeClusters(c, ByteArray(csize))
            buf.clusters = buf.clusters + c[0]
            buf.data = buf.data + ByteArray(csize)
        }
        System.arraycopy(entries, 0, buf.data, at, entries.size)
        return true
    }

    private fun shortsIn(data: ByteArray): Set<String> {
        val set = HashSet<String>(); var i = 0
        while (i + 32 <= data.size) {
            val f = data[i].toInt() and 0xFF
            if (f == 0) break
            if (f != 0xE5 && (data[i + 11].toInt() and 0xFF) != ATTR_LFN) set.add(String(data, i, 11, Charsets.ISO_8859_1))
            i += 32
        }
        return set
    }

    // ── FileSystemWriter ──────────────────────────────────────────────────────

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean =
        writeFileStream(parentEntry, name, data.size.toLong(), java.io.ByteArrayInputStream(data), null)

    override fun writeFileStream(
        parentEntry: FileSystemEntry, name: String, size: Long,
        input: java.io.InputStream, onProgress: ((Long) -> Unit)?
    ): Boolean = guarded("writeFile $name") {
        if (!validName(name) || size < 0 || size > 0xFFFFFFFFL) return@guarded false
        val dir = dirOf(parentEntry) ?: return@guarded false
        val buf = open(dir)
        if (scan(buf.data).any { it.name.equals(name, true) }) return@guarded false
        val n = ((size + csize - 1) / csize).toInt()
        val cl = allocate(n) ?: return@guarded false
        try {
            val chunkClusters = ((1 shl 20) / csize).coerceAtLeast(1)
            val rb = ByteArray(chunkClusters * csize)
            var idx = 0; var written = 0L
            while (idx < n) {
                val cnt = minOf(chunkClusters, n - idx)
                val want = minOf((cnt * csize).toLong(), size - written).toInt()
                var got = 0
                while (got < want) { val r = input.read(rb, got, want - got); if (r < 0) break; got += r }
                if (got < want) throw java.io.IOException("source ended early")
                java.util.Arrays.fill(rb, got, cnt * csize, 0)
                writeClusters(cl.subList(idx, idx + cnt), rb.copyOf(cnt * csize))
                idx += cnt; written += got
                onProgress?.invoke(written)
            }
            val short = uniqueShort(name, shortsIn(buf.data))
            val ents = buildEntries(name, short, ATTR_ARCHIVE, cl.firstOrNull() ?: 0L, size, System.currentTimeMillis())
            if (!insert(buf, ents)) throw java.io.IOException("directory full")
            flushFat(); writeClusters(buf.clusters, buf.data); dev.flushCache()
            true
        } catch (e: Exception) {
            Log.e(TAG, "FAT32 write aborted, releasing clusters", e)
            cl.firstOrNull()?.let { freeChain(it) }; flushFat()
            false
        }
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean = guarded("createDirectory $name") {
        if (!validName(name)) return@guarded false
        val dir = dirOf(parentEntry) ?: return@guarded false
        val buf = open(dir)
        if (scan(buf.data).any { it.name.equals(name, true) }) return@guarded false
        val c = allocate(1) ?: return@guarded false
        val now = System.currentTimeMillis()
        val body = ByteArray(csize)
        val dotParent = if (dir.first == header.rootCluster) 0L else dir.first
        System.arraycopy(buildEntries(".", ".          ", ATTR_DIR, c[0], 0, now), 0, body, 0, 32)
        System.arraycopy(buildEntries("..", "..         ", ATTR_DIR, dotParent, 0, now), 0, body, 32, 32)
        writeClusters(c, body)
        val short = uniqueShort(name, shortsIn(buf.data))
        if (!insert(buf, buildEntries(name, short, ATTR_DIR, c[0], 0, now))) { freeChain(c[0]); return@guarded false }
        flushFat(); writeClusters(buf.clusters, buf.data); dev.flushCache()
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean = guarded("deleteEntry ${entry.name}") {
        val dir = dirForPath(entry.path.substringBeforeLast('/', "")) ?: return@guarded false
        val buf = open(dir)
        val it = scan(buf.data).firstOrNull { x -> x.name.equals(entry.name, true) } ?: return@guarded false
        remove(buf, it)
        flushFat(); writeClusters(buf.clusters, buf.data); dev.flushCache()
    }

    private fun remove(buf: Buf, it: Item) {
        if (it.attr and ATTR_DIR != 0 && it.first >= 2) {
            val sub = Buf(it.first, chain(it.first), readClusters(chain(it.first)))
            for (c in scan(sub.data)) if (c.name != "." && c.name != "..") remove(sub, c)
            writeClusters(sub.clusters, sub.data)
        }
        if (it.first >= 2) freeChain(it.first)
        for (k in 0 until it.count) buf.data[it.off + k * 32] = 0xE5.toByte()
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean = guarded("renameEntry ${entry.name}") {
        if (!validName(newName)) return@guarded false
        val dir = dirForPath(entry.path.substringBeforeLast('/', "")) ?: return@guarded false
        val buf = open(dir)
        val items = scan(buf.data)
        val it = items.firstOrNull { x -> x.name.equals(entry.name, true) } ?: return@guarded false
        if (!it.name.equals(newName, true) && items.any { x -> x.name.equals(newName, true) }) return@guarded false
        val shortOff = it.off + (it.count - 1) * 32
        val old = buf.data.copyOfRange(shortOff, shortOff + 32)
        for (k in 0 until it.count) buf.data[it.off + k * 32] = 0xE5.toByte()
        val short = uniqueShort(newName, shortsIn(buf.data))
        val ents = buildEntries(newName, short, it.attr, it.first, it.size, System.currentTimeMillis())
        System.arraycopy(old, 13, ents, ents.size - 32 + 13, 11)     // keep create time/date, access date
        if (!insert(buf, ents)) return@guarded false
        flushFat(); writeClusters(buf.clusters, buf.data); dev.flushCache()
    }

    private inline fun guarded(what: String, block: () -> Boolean): Boolean = try {
        block().also { if (!it) Log.w(TAG, "FAT32 $what failed") }
    } catch (e: Exception) { Log.e(TAG, "FAT32 $what threw", e); false }

    private fun le16(a: ByteArray, o: Int): Long = ((a[o].toLong() and 0xFF) or ((a[o + 1].toLong() and 0xFF) shl 8))
    private fun le32(a: ByteArray, o: Int): Long = le16(a, o) or (le16(a, o + 2) shl 16)
    private fun putLe16(a: ByteArray, o: Int, v: Int) { a[o] = v.toByte(); a[o + 1] = (v shr 8).toByte() }
    private fun putLe32(a: ByteArray, o: Int, v: Long) { for (k in 0..3) a[o + k] = (v shr (8 * k)).toByte() }
}
