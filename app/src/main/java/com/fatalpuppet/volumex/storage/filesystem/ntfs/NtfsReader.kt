package com.fatalpuppet.volumex.storage.filesystem.ntfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import java.io.OutputStream

/**
 * Read-only NTFS: MFT records with fixups, attribute lists, resident / non-resident / sparse / LZNT1-compressed data,
 * and B+tree directory indexes. Encrypted (EFS) and BitLocker volumes are not readable.
 */
class NtfsReader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    companion object {
        private const val TAG = "VolumeX"
        const val ROOT = 5L
        private const val ATTR_STDINFO = 0x10; private const val ATTR_LIST = 0x20; private const val ATTR_NAME = 0x30
        private const val ATTR_DATA = 0x80; private const val ATTR_VOLNAME = 0x60
        private const val ATTR_INDEX_ROOT = 0x90; private const val ATTR_INDEX_ALLOC = 0xA0
        private const val FT_EPOCH_DIFF_MS = 11644473600000L

        fun isNtfs(bootSector: ByteArray) = bootSector.size >= 11 && String(bootSector, 3, 8, Charsets.ISO_8859_1) == "NTFS    "
        fun isBitLocker(bootSector: ByteArray) = bootSector.size >= 11 && String(bootSector, 3, 8, Charsets.ISO_8859_1) == "-FVE-FS-"
    }

    internal var bytesPerSector = 512; private set
    internal var clusterSize = 4096; private set
    internal var recSize = 1024; private set
    internal var idxBlockSize = 4096; private set
    internal var totalClusters = 0L; private set
    private var serial = 0L
    private var mftLcn = 0L
    private var sectorsPerCluster = 8
    internal var mft: Stream? = null; private set
    private var mounted = false

    // ---- low level ----
    internal class Run(val vcn: Long, val len: Long, val lcn: Long)   // lcn < 0 = sparse hole
    internal class Attr(
        val type: Int, val name: String, val nonResident: Boolean, val flags: Int,
        val resident: ByteArray?, val startVcn: Long, val runs: List<Run>,
        val realSize: Long, val initSize: Long, val compUnit: Int
    )

    internal inner class Stream(val runs: List<Run>, val realSize: Long, val initSize: Long, val compressed: Boolean, val unitClusters: Int, val resident: ByteArray?) {
        private var unitCache: ByteArray? = null
        private var unitCacheIdx = -1L

        /** Absolute device sector (512 B) that holds byte [offset] of this non-resident, uncompressed stream; -1 if unmapped. */
        fun physSector(offset: Long): Long {
            if (resident != null || compressed) return -1
            val vcn = offset / clusterSize
            val r = runAt(vcn) ?: return -1
            if (r.lcn < 0) return -1
            return startLba + ((r.lcn + (vcn - r.vcn)) * clusterSize + offset % clusterSize) / bytesPerSector
        }

        fun read(offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
            if (offset >= realSize) return 0
            val want = minOf(len.toLong(), realSize - offset).toInt()
            if (resident != null) { System.arraycopy(resident, offset.toInt(), buf, bufOff, want); return want }
            var done = 0
            while (done < want) {
                val pos = offset + done
                val n = if (pos >= initSize) { val z = want - done; java.util.Arrays.fill(buf, bufOff + done, bufOff + done + z, 0); z }
                else if (compressed) readCompressed(pos, buf, bufOff + done, minOf(want - done, (initSize - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                else readPlain(pos, buf, bufOff + done, minOf(want - done, (initSize - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                if (n <= 0) return if (done > 0) done else -1
                done += n
            }
            return done
        }

        private fun runAt(vcn: Long): Run? {
            var lo = 0; var hi = runs.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1; val r = runs[mid]
                if (vcn < r.vcn) hi = mid - 1 else if (vcn >= r.vcn + r.len) lo = mid + 1 else return r
            }
            return null
        }

        private fun readPlain(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            val vcn = pos / clusterSize
            val r = runAt(vcn)
            val inClu = (pos % clusterSize).toInt()
            val avail = ((r?.let { it.vcn + it.len - vcn } ?: 1L) * clusterSize - inClu).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val n = minOf(len, avail)
            if (r == null || r.lcn < 0) { java.util.Arrays.fill(buf, off, off + n, 0); return n }
            val byteStart = (r.lcn + (vcn - r.vcn)) * clusterSize + inClu
            val lba = startLba + byteStart / bytesPerSector
            val skip = (byteStart % bytesPerSector).toInt()
            val data = dev.readSectors(lba, (skip + n + bytesPerSector - 1) / bytesPerSector) ?: return -1
            System.arraycopy(data, skip, buf, off, n)
            return n
        }

        private fun readCompressed(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            val unitBytes = unitClusters * clusterSize
            val unitIdx = pos / unitBytes
            val unit = loadUnit(unitIdx) ?: return -1
            val inUnit = (pos - unitIdx * unitBytes).toInt()
            val n = minOf(len, unitBytes - inUnit)
            System.arraycopy(unit, inUnit, buf, off, n)
            return n
        }

        private fun loadUnit(idx: Long): ByteArray? {
            if (idx == unitCacheIdx) return unitCache
            val unitBytes = unitClusters * clusterSize
            val first = idx * unitClusters
            // clusters of this unit that are really on disk
            var stored = 0
            val lcnList = ArrayList<Long>()
            for (c in 0 until unitClusters) {
                val r = runAt(first + c)
                if (r == null || r.lcn < 0) break
                lcnList.add(r.lcn + (first + c - r.vcn)); stored++
            }
            val out = ByteArray(unitBytes)
            if (stored > 0) {
                val raw = ByteArray(stored * clusterSize)
                var i = 0
                while (i < stored) {                         // read contiguous cluster groups in one go
                    var j = i + 1
                    while (j < stored && lcnList[j] == lcnList[j - 1] + 1) j++
                    val byteStart = lcnList[i] * clusterSize
                    val data = dev.readSectors(startLba + byteStart / bytesPerSector, (j - i) * clusterSize / bytesPerSector) ?: return null
                    System.arraycopy(data, 0, raw, i * clusterSize, data.size)
                    i = j
                }
                if (stored == unitClusters) System.arraycopy(raw, 0, out, 0, unitBytes)
                else Lznt1.decompress(raw, raw.size, out)
            }
            unitCache = out; unitCacheIdx = idx
            return out
        }
    }

    internal fun filetimeToMs(ft: Long) = if (ft <= 0) 0L else ft / 10_000 - FT_EPOCH_DIFF_MS
    internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    internal fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    internal fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

    /** Restores the bytes that the update-sequence array replaced at the end of every 512-byte stride. */
    internal fun applyFixups(b: ByteArray, usaOff: Int, usaCount: Int): Boolean {
        if (usaCount < 2 || usaOff + usaCount * 2 > b.size) return false
        val usn0 = b[usaOff]; val usn1 = b[usaOff + 1]
        for (i in 1 until usaCount) {
            val end = i * 512
            if (end > b.size) break
            if (b[end - 2] != usn0 || b[end - 1] != usn1) return false
            b[end - 2] = b[usaOff + i * 2]; b[end - 1] = b[usaOff + i * 2 + 1]
        }
        return true
    }

    internal fun decodeRuns(b: ByteArray, start: Int, end: Int, startVcn: Long): List<Run> {
        val out = ArrayList<Run>(); var p = start; var vcn = startVcn; var lcn = 0L
        while (p < end) {
            val h = b[p].toInt() and 0xFF
            if (h == 0) break
            val lenSz = h and 0xF; val offSz = h shr 4
            p++
            if (p + lenSz + offSz > end + 0 && p + lenSz + offSz > b.size) break
            var len = 0L
            for (k in 0 until lenSz) len = len or ((b[p + k].toLong() and 0xFF) shl (8 * k))
            p += lenSz
            if (offSz == 0) out.add(Run(vcn, len, -1))
            else {
                var off = 0L
                for (k in 0 until offSz) off = off or ((b[p + k].toLong() and 0xFF) shl (8 * k))
                if (b[p + offSz - 1] < 0) off -= 1L shl (8 * offSz)   // signed
                lcn += off
                out.add(Run(vcn, len, lcn))
            }
            p += offSz; vcn += len
        }
        return out
    }

    internal fun parseAttrs(rec: ByteArray): List<Attr> {
        val out = ArrayList<Attr>()
        var p = u16(rec, 0x14)
        while (p + 8 <= rec.size) {
            val type = u32(rec, p).toInt()
            if (type == -1) break
            val len = u32(rec, p + 4).toInt()
            if (len <= 0 || p + len > rec.size) break
            val nonRes = rec[p + 8].toInt() != 0
            val nameLen = rec[p + 9].toInt() and 0xFF
            val nameOff = u16(rec, p + 10)
            val flags = u16(rec, p + 12)
            val name = if (nameLen > 0) String(rec, p + nameOff, nameLen * 2, Charsets.UTF_16LE) else ""
            if (!nonRes) {
                val cl = u32(rec, p + 0x10).toInt(); val co = u16(rec, p + 0x14)
                if (p + co + cl <= rec.size) out.add(Attr(type, name, false, flags, rec.copyOfRange(p + co, p + co + cl), 0, emptyList(), cl.toLong(), cl.toLong(), 0))
            } else {
                val startVcn = u64(rec, p + 0x10)
                val runOff = u16(rec, p + 0x20)
                out.add(Attr(type, name, true, flags, null, startVcn, decodeRuns(rec, p + runOff, p + len, startVcn), u64(rec, p + 0x30), u64(rec, p + 0x38), u16(rec, p + 0x22)))
            }
            p += len
        }
        return out
    }

    internal fun readRecord(n: Long): ByteArray? {
        val m = mft
        val buf = ByteArray(recSize)
        if (m == null) {                                       // bootstrap: $MFT record 0 at the boot-sector MFT cluster
            return null
        }
        if (m.read(n * recSize, buf, 0, recSize) != recSize) return null
        if (!(buf[0] == 'F'.code.toByte() && buf[1] == 'I'.code.toByte() && buf[2] == 'L'.code.toByte() && buf[3] == 'E'.code.toByte())) return null
        if (!applyFixups(buf, u16(buf, 4), u16(buf, 6))) return null
        return buf
    }

    private val attrCache = object : LinkedHashMap<Long, List<Attr>?>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<Attr>?>) = size > 512 }

    /** All attributes of a file including those stored in extension records (via $ATTRIBUTE_LIST). */
    internal fun allAttrs(n: Long): List<Attr>? {
        if (attrCache.containsKey(n)) return attrCache[n]
        val rec = readRecord(n) ?: return null.also { attrCache[n] = null }
        if (u16(rec, 0x16) and 1 == 0) return null.also { attrCache[n] = null }     // record not in use
        var attrs = parseAttrs(rec)
        val list = attrs.firstOrNull { it.type == ATTR_LIST }
        if (list != null) {
            val data = streamOf(listOf(list))?.let { s -> ByteArray(s.realSize.toInt().coerceAtMost(1 shl 22)).also { s.read(0, it, 0, it.size) } }
            if (data != null) {
                val seen = HashSet<Long>(); seen.add(n)
                val extra = ArrayList<Attr>()
                var p = 0
                while (p + 26 <= data.size) {
                    val l = u16(data, p + 4); if (l <= 0) break
                    val ref = u64(data, p + 0x10) and 0xFFFFFFFFFFFFL
                    if (seen.add(ref)) readRecord(ref)?.let { extra += parseAttrs(it) }
                    p += l
                }
                attrs = attrs + extra
            }
        }
        attrCache[n] = attrs
        return attrs
    }

    /** Builds a data stream from all fragments of one attribute (same type and name). */
    internal fun streamOf(frags: List<Attr>): Stream? {
        val sorted = frags.sortedBy { it.startVcn }
        val first = sorted.firstOrNull() ?: return null
        if (!first.nonResident) return Stream(emptyList(), first.resident!!.size.toLong(), first.resident.size.toLong(), false, 0, first.resident)
        val runs = sorted.flatMap { it.runs }.sortedBy { it.vcn }
        val compressed = first.flags and 0x0001 != 0
        return Stream(runs, first.realSize, first.initSize, compressed, if (compressed) 1 shl first.compUnit else 0, null)
    }

    internal fun dataStream(n: Long): Stream? {
        val attrs = allAttrs(n) ?: return null
        if (attrs.any { it.type == ATTR_DATA && it.flags and 0x4000 != 0 }) return null       // EFS-encrypted
        return streamOf(attrs.filter { it.type == ATTR_DATA && it.name.isEmpty() })
    }

    // ---- mount ----
    override fun mount(): Boolean {
        val boot = dev.readSector(startLba) ?: return false
        if (!isNtfs(boot) || u16(boot, 510) != 0xAA55) return false
        bytesPerSector = u16(boot, 0x0B)
        if (bytesPerSector != 512) { Log.w(TAG, "NTFS: only 512-byte sectors are supported"); return false }
        val spc = boot[0x0D].toInt() and 0xFF
        clusterSize = bytesPerSector * spc
        val totalSectors = u64(boot, 0x28)
        totalClusters = totalSectors / spc
        val mftClu = u64(boot, 0x30)
        mftLcn = mftClu; sectorsPerCluster = spc
        val cpr = boot[0x40].toInt()                                  // signed
        recSize = if (cpr > 0) cpr * clusterSize else 1 shl (-cpr)
        val cpi = boot[0x44].toInt()
        idxBlockSize = if (cpi > 0) cpi * clusterSize else 1 shl (-cpi)
        serial = u64(boot, 0x48)

        // $MFT record 0 gives the runs of the MFT itself
        val raw = dev.readSectors(startLba + mftClu * spc, recSize / bytesPerSector) ?: return false
        if (String(raw, 0, 4, Charsets.ISO_8859_1) != "FILE" || !applyFixups(raw, u16(raw, 4), u16(raw, 6))) return false
        var attrs = parseAttrs(raw)
        val data = attrs.filter { it.type == ATTR_DATA && it.name.isEmpty() }
        mft = streamOf(data) ?: return false
        // MFT fragmented across extension records: pull them in through the partial runlist
        val list = attrs.firstOrNull { it.type == ATTR_LIST }
        if (list != null) {
            attrCache.clear()
            val all = allAttrs(0)
            if (all != null) mft = streamOf(all.filter { it.type == ATTR_DATA && it.name.isEmpty() })
        }
        mounted = mft != null && readRecord(ROOT) != null
        Log.i(TAG, "NTFS mounted=$mounted cluster=$clusterSize rec=$recSize")
        return mounted
    }

    // ---- directory index ----
    private class Child(val rec: Long, val parent: Long, val name: String, val ns: Int, val flags: Long, val created: Long, val modified: Long, val size: Long)

    private fun parseIndexEntries(buf: ByteArray, first: Int, end: Int, children: MutableList<Child>, subVcns: MutableList<Long>) {
        var p = first
        while (p + 16 <= end) {
            val entLen = u16(buf, p + 8); val keyLen = u16(buf, p + 10); val fl = u16(buf, p + 12)
            if (entLen < 16 || p + entLen > buf.size) break
            if (keyLen > 0x42 && fl and 2 == 0) {
                val k = p + 16
                val ref = u64(buf, p) and 0xFFFFFFFFFFFFL
                val nameLen = buf[k + 0x40].toInt() and 0xFF
                if (k + 0x42 + nameLen * 2 <= buf.size) children.add(Child(
                    ref, u64(buf, k) and 0xFFFFFFFFFFFFL, String(buf, k + 0x42, nameLen * 2, Charsets.UTF_16LE), buf[k + 0x41].toInt() and 0xFF,
                    u32(buf, k + 0x38), filetimeToMs(u64(buf, k + 0x08)), filetimeToMs(u64(buf, k + 0x18)), u64(buf, k + 0x30)
                ))
            }
            if (fl and 1 != 0) subVcns.add(u64(buf, p + entLen - 8))
            if (fl and 2 != 0) break
            p += entLen
        }
    }

    private fun readDirectory(n: Long): List<Child> {
        val attrs = allAttrs(n) ?: return emptyList()
        val root = attrs.firstOrNull { it.type == ATTR_INDEX_ROOT && it.name == "\$I30" }?.resident ?: return emptyList()
        val blockSize = u32(root, 8).toInt().takeIf { it > 0 } ?: idxBlockSize
        val nodeHdr = 16
        val children = ArrayList<Child>(); val subs = ArrayList<Long>()
        parseIndexEntries(root, nodeHdr + u32(root, nodeHdr).toInt(), minOf(root.size, nodeHdr + u32(root, nodeHdr + 4).toInt()), children, subs)
        if (subs.isNotEmpty()) {
            val alloc = streamOf(attrs.filter { it.type == ATTR_INDEX_ALLOC && it.name == "\$I30" }) ?: return children
            val unit = if (blockSize >= clusterSize) clusterSize else 512
            val seen = HashSet<Long>(); val queue = ArrayDeque(subs)
            while (queue.isNotEmpty() && seen.size < 200_000) {
                val vcn = queue.removeFirst(); if (!seen.add(vcn)) continue
                val blk = ByteArray(blockSize)
                if (alloc.read(vcn * unit, blk, 0, blockSize) != blockSize) continue
                if (String(blk, 0, 4, Charsets.ISO_8859_1) != "INDX" || !applyFixups(blk, u16(blk, 4), u16(blk, 6))) continue
                val s2 = ArrayList<Long>()
                parseIndexEntries(blk, 0x18 + u32(blk, 0x18).toInt(), minOf(blk.size, 0x18 + u32(blk, 0x1C).toInt()), children, s2)
                queue.addAll(s2)
            }
        }
        return children
    }

    private val dirCache = object : LinkedHashMap<Long, List<FileSystemEntry>>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<FileSystemEntry>>) = size > 64 }

    private fun listRecord(n: Long, path: String): List<FileSystemEntry> {
        dirCache[n]?.let { return it.map { e -> e.copy(path = join(path, e.name)) } }
        val entries = readDirectory(n).asSequence()
            .filter { it.ns != 2 && it.rec >= 24 }                  // hide DOS 8.3 duplicates and $MFT & friends
            .map { c ->
                val isDir = c.flags and 0x10000000L != 0L
                var size = 0L
                if (!isDir) size = dataStream(c.rec)?.realSize ?: c.size
                FileSystemEntry(
                    name = c.name, path = "", isDirectory = isDir, size = size, createdAt = c.created, modifiedAt = c.modified,
                    inodeOid = c.rec, parentOid = n
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).toList()
        dirCache[n] = entries
        return entries.map { it.copy(path = join(path, it.name)) }
    }

    private fun join(dir: String, name: String) = if (dir == "/" || dir.isEmpty()) "/$name" else "${dir.trimEnd('/')}/$name"

    private fun resolve(path: String): Long? {
        var cur = ROOT
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = listRecord(cur, "/").firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) }?.inodeOid ?: return null
        }
        return cur
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val n = resolve(path) ?: return emptyList()
        return listRecord(n, path)
    }

    override fun rootEntry(volumeIndex: Int) = FileSystemEntry("/", "/", true, 0, 0, 0, inodeOid = ROOT)

    // ---- data ----
    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val s = dataStream(entry.inodeOid) ?: return null
        if (s.realSize > Int.MAX_VALUE - 8) return null
        val out = ByteArray(s.realSize.toInt())
        return if (s.read(0, out, 0, out.size) == out.size || out.isEmpty()) out else null
    }

    override fun readFileTo(entry: FileSystemEntry, out: OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val s = dataStream(entry.inodeOid) ?: return false
        val buf = ByteArray(1 shl 20); var pos = 0L
        while (pos < s.realSize) {
            val n = s.read(pos, buf, 0, buf.size)
            if (n <= 0) return false
            out.write(buf, 0, n); pos += n; onProgress?.invoke(pos)
        }
        return true
    }

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        val s = dataStream(entry.inodeOid) ?: return -1
        return s.read(offset, buf, bufOff, len)
    }

    // ---- volume ----
    private fun volumeLabel(): String {
        val a = allAttrs(3)?.firstOrNull { it.type == ATTR_VOLNAME }?.resident ?: return ""
        return String(a, Charsets.UTF_16LE)
    }

    private var freeClusters: Long? = null
    private fun countFree(): Long? {
        freeClusters?.let { return it }
        val s = dataStream(6) ?: return null
        if (s.realSize > 96L * 1024 * 1024) return null
        val buf = ByteArray(1 shl 20); var pos = 0L; var used = 0L
        while (pos < s.realSize) {
            val n = s.read(pos, buf, 0, buf.size); if (n <= 0) return null
            for (i in 0 until n) { val v = buf[i].toInt() and 0xFF; used += Integer.bitCount(v) }
            pos += n
        }
        return (totalClusters - used).coerceIn(0, totalClusters).also { freeClusters = it }
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val free = countFree()
        return listOf(VolumeInfo(
            name = volumeLabel().ifBlank { "NTFS Volume" }, type = "NTFS", uuid = "%016X".format(serial),
            totalBlocks = totalClusters, blockSize = clusterSize.toLong(), freeBlocks = free ?: 0L, freeKnown = free != null
        ))
    }

    /** Re-reads $MFT's own record (after the writer grew the MFT) so record lookups see the new runlist. */
    internal fun reloadMft(): Boolean {
        val raw = dev.readSectors(startLba + mftLcn * sectorsPerCluster, recSize / bytesPerSector) ?: return false
        if (String(raw, 0, 4, Charsets.ISO_8859_1) != "FILE" || !applyFixups(raw, u16(raw, 4), u16(raw, 6))) return false
        mft = streamOf(parseAttrs(raw).filter { it.type == ATTR_DATA && it.name.isEmpty() }) ?: return false
        invalidate()
        return true
    }

    /** Drops cached records / listings after a write so the next read sees the new state. */
    internal fun invalidate() { attrCache.clear(); dirCache.clear(); freeClusters = null }

    internal val device: BlockDeviceReader get() = dev
    internal val volumeStartLba: Long get() = startLba

    override fun unmount() { mounted = false; mft = null; attrCache.clear(); dirCache.clear() }
}
