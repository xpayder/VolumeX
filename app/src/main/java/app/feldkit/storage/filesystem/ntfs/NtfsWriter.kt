package app.feldkit.storage.filesystem.ntfs

import android.util.Log
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemWriter
import java.io.InputStream

/**
 * Experimental NTFS writer for clean (cleanly dismounted, not hibernated) volumes. It creates, renames and deletes files and
 * folders and updates the MFT, the cluster bitmap and the directory B+tree in an order that leaves at worst unreferenced
 * clusters if it is interrupted. It does not write the $LogFile / $UsnJrnl.
 *
 * Deliberate limits (it refuses instead of risking damage): a directory index that would need a block split, removing an entry
 * that sits in an interior index node or would empty an index block, files that use $ATTRIBUTE_LIST, hard-linked or
 * EFS-encrypted / compressed files, and an MFT with no free record.
 */
class NtfsWriter(private val r: NtfsReader) : FileSystemWriter {
    companion object {
        private const val TAG = "FeldKit"
        private const val FT_EPOCH_DIFF_MS = 11644473600000L
        private const val ATTR_STDINFO = 0x10; private const val ATTR_LIST = 0x20; private const val ATTR_NAME = 0x30
        private const val ATTR_DATA = 0x80; private const val ATTR_INDEX_ROOT = 0x90; private const val ATTR_INDEX_ALLOC = 0xA0
        private const val ATTR_BITMAP = 0xB0; private const val ATTR_VOLINFO = 0x70
        private const val MAX_RUNS = 48
        /** Why the last operation was refused (for logs / UI), or null. */
        @Volatile var lastRefusal: String? = null
    }

    private val dev = r.device
    private val start = r.volumeStartLba
    private val cs get() = r.clusterSize
    private val rs get() = r.recSize

    private var checked: Boolean? = null
    private var upcase = CharArray(0)
    private var volBitmap: BitFile? = null
    private var mftBitmap: BitFile? = null
    private var allocHint = 0L

    private fun refuse(why: String): Boolean { lastRefusal = why; Log.w(TAG, "NTFS write refused: $why"); return false }

    // ---------------------------------------------------------------- low level
    private fun u16(b: ByteArray, o: Int) = r.u16(b, o)
    private fun u32(b: ByteArray, o: Int) = r.u32(b, o)
    private fun u64(b: ByteArray, o: Int) = r.u64(b, o)
    private fun p16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun p32(b: ByteArray, o: Int, v: Long) { for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun p64(b: ByteArray, o: Int, v: Long) { for (i in 0..7) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun align8(n: Int) = (n + 7) and 7.inv()
    private fun nowFt() = (System.currentTimeMillis() + FT_EPOCH_DIFF_MS) * 10_000

    private fun writeSectors(lba: Long, data: ByteArray) { if (!dev.writeSectors(lba, data)) throw WriteFail("device write failed at sector $lba") }
    private class WriteFail(msg: String) : Exception(msg)

    /** Writes [data] (whole sectors) at byte [offset] of a non-resident stream. */
    private fun writeStream(s: NtfsReader.Stream, offset: Long, data: ByteArray) {
        require(offset % 512 == 0L && data.size % 512 == 0)
        var o = 0
        while (o < data.size) {
            val lba = s.physSector(offset + o)
            if (lba < 0) throw WriteFail("unmapped stream offset ${offset + o}")
            // contiguous sectors in one device write
            var n = 1
            while (o + (n + 1) * 512 <= data.size && s.physSector(offset + o + n * 512) == lba + n) n++
            writeSectors(lba, data.copyOfRange(o, o + n * 512)); o += n * 512
        }
    }

    /** A bit array stored in a non-resident stream ($Bitmap or the $MFT's $BITMAP), cached 4 KB at a time. */
    private inner class BitFile(val stream: NtfsReader.Stream, val bitCount: Long) {
        private val pages = HashMap<Long, ByteArray>()
        private val dirty = HashSet<Long>()
        private fun page(i: Long): ByteArray = pages.getOrPut(i) {
            val b = ByteArray(4096)
            val off = i * 4096
            if (off < stream.realSize) stream.read(off, b, 0, minOf(4096L, stream.realSize - off).toInt())
            b
        }
        fun get(bit: Long): Boolean {
            if (bit >= bitCount) return true
            val p = page(bit / 32768); val i = (bit % 32768).toInt()
            return (p[i / 8].toInt() shr (i % 8)) and 1 != 0
        }
        fun set(bit: Long, v: Boolean) {
            val pi = bit / 32768; val p = page(pi); val i = (bit % 32768).toInt()
            p[i / 8] = if (v) (p[i / 8].toInt() or (1 shl (i % 8))).toByte() else (p[i / 8].toInt() and (1 shl (i % 8)).inv()).toByte()
            dirty.add(pi)
        }
        /** First clear bit at or after [from] (wrapping once), or -1. */
        fun findClear(from: Long): Long {
            var b = from.coerceIn(0, bitCount)
            var wrapped = false
            while (true) {
                if (b >= bitCount) { if (wrapped || from == 0L) return -1; wrapped = true; b = 0 }
                if (wrapped && b >= from) return -1
                val p = page(b / 32768); val i = (b % 32768).toInt()
                if (i % 8 == 0 && p[i / 8] == 0xFF.toByte()) { b += 8; continue }
                if ((p[i / 8].toInt() shr (i % 8)) and 1 == 0) return b
                b++
            }
        }
        fun flush() {
            for (pi in dirty.sorted()) {
                val off = pi * 4096
                val limit = ((stream.realSize + 511) / 512) * 512
                val len = minOf(4096L, limit - off).toInt()
                if (len > 0) writeStream(stream, off, page(pi).copyOf(len))
            }
            dirty.clear()
        }
        fun discard() { pages.clear(); dirty.clear() }
    }

    // ---------------------------------------------------------------- records
    private fun rawRecord(n: Long): ByteArray? = r.readRecord(n)?.copyOf()

    private fun applyWriteFixups(b: ByteArray, stride: Int = 512) {
        val usa = u16(b, 4); val count = u16(b, 6)
        var usn = (u16(b, usa) + 1) and 0xFFFF
        if (usn == 0 || usn == 0xFFFF) usn = 1
        p16(b, usa, usn)
        for (i in 1 until count) {
            val end = i * stride
            b[usa + i * 2] = b[end - 2]; b[usa + i * 2 + 1] = b[end - 1]
            b[end - 2] = usn.toByte(); b[end - 1] = (usn ushr 8).toByte()
        }
    }

    private fun writeRecord(n: Long, rec: ByteArray) {
        val m = r.mft ?: throw WriteFail("no MFT")
        val out = rec.copyOf()
        applyWriteFixups(out)
        writeStream(m, n * rs, out)
        if (n < 4) {                                   // $MFTMirr mirrors the first four records
            val mirr = r.dataStream(1)
            if (mirr != null && mirr.physSector(n * rs) >= 0) writeStream(mirr, n * rs, out)
        }
    }

    private fun findAttr(rec: ByteArray, type: Int, name: String? = null): Int {
        var p = u16(rec, 0x14)
        while (p + 8 <= rec.size) {
            val t = u32(rec, p).toInt()
            if (t == -1) return -1
            val len = u32(rec, p + 4).toInt()
            if (len <= 0 || p + len > rec.size) return -1
            if (t == type) {
                val nl = rec[p + 9].toInt() and 0xFF
                val an = if (nl > 0) String(rec, p + u16(rec, p + 10), nl * 2, Charsets.UTF_16LE) else ""
                if (name == null || an == name) return p
            }
            p += len
        }
        return -1
    }

    /** Offsets of all attributes of [type]. */
    private fun findAll(rec: ByteArray, type: Int): List<Int> {
        val out = ArrayList<Int>()
        var p = u16(rec, 0x14)
        while (p + 8 <= rec.size) {
            val t = u32(rec, p).toInt(); if (t == -1) break
            val len = u32(rec, p + 4).toInt(); if (len <= 0 || p + len > rec.size) break
            if (t == type) out.add(p)
            p += len
        }
        return out
    }

    /** Grows / shrinks a resident attribute's value in place (shifting the rest of the record); false if the record would overflow. */
    private fun resizeResident(rec: ByteArray, ao: Int, newLen: Int): Boolean {
        val oldAttrLen = u32(rec, ao + 4).toInt(); val valOff = u16(rec, ao + 0x14)
        val newAttrLen = align8(valOff + newLen)
        val delta = newAttrLen - oldAttrLen
        val used = u32(rec, 0x18).toInt()
        if (used + delta > u32(rec, 0x1C).toInt()) return false
        System.arraycopy(rec, ao + oldAttrLen, rec, ao + newAttrLen, used - (ao + oldAttrLen))
        if (delta < 0) java.util.Arrays.fill(rec, used + delta, used, 0)
        p32(rec, ao + 4, newAttrLen.toLong()); p32(rec, ao + 0x10, newLen.toLong()); p32(rec, 0x18, (used + delta).toLong())
        return true
    }

    private fun removeAttr(rec: ByteArray, ao: Int) {
        val len = u32(rec, ao + 4).toInt(); val used = u32(rec, 0x18).toInt()
        System.arraycopy(rec, ao + len, rec, ao, used - (ao + len))
        java.util.Arrays.fill(rec, used - len, used, 0)
        p32(rec, 0x18, (used - len).toLong())
    }

    // ---------------------------------------------------------------- preflight
    private fun preflight(): Boolean {
        checked?.let { return it }
        val ok = try { doPreflight() } catch (e: Exception) { Log.e(TAG, "NTFS preflight failed", e); refuse("preflight error ${e.message}") }
        checked = ok
        return ok
    }

    private fun doPreflight(): Boolean {
        // $Volume: the dirty flag means Windows did not dismount cleanly; its journal would have to be replayed first
        val vol = rawRecord(3) ?: return refuse("cannot read \$Volume")
        val vi = findAttr(vol, ATTR_VOLINFO)
        if (vi < 0) return refuse("no volume information")
        val flags = u16(vol, vi + u16(vol, vi + 0x14) + 10)
        if (flags and 0x0001 != 0) return refuse("volume is marked dirty (not cleanly dismounted) - run chkdsk / eject it from Windows first")
        // Fast Startup / hibernation leaves the file system in a saved state
        r.listDirectory(0, "/").firstOrNull { it.name.equals("hiberfil.sys", true) }?.let { h ->
            val b = ByteArray(8)
            if (r.readRange(h, 0, b, 0, 8) == 8) {
                val sig = String(b, 0, 4, Charsets.ISO_8859_1)
                if (sig.equals("hibr", true) || sig == "wake" || sig == "WAKE") return refuse("Windows is hibernated or in Fast Startup state - shut it down fully first")
            }
        }
        // $UpCase for name collation
        val up = r.dataStream(10) ?: return refuse("no \$UpCase")
        val ub = ByteArray(131072)
        if (up.read(0, ub, 0, ub.size) != ub.size) return refuse("cannot read \$UpCase")
        upcase = CharArray(65536) { ((ub[2 * it].toInt() and 0xFF) or ((ub[2 * it + 1].toInt() and 0xFF) shl 8)).toChar() }
        // cluster bitmap and MFT bitmap must be ordinary non-resident streams
        val bm = r.dataStream(6) ?: return refuse("no \$Bitmap")
        if (bm.resident != null || bm.physSector(0) < 0) return refuse("\$Bitmap not writable")
        volBitmap = BitFile(bm, r.totalClusters)
        if (!initMftBitmap()) return false
        allocHint = r.totalClusters / 8
        return true
    }

    private fun initMftBitmap(): Boolean {
        val mrec = rawRecord(0) ?: return refuse("no \$MFT record")
        val mb = findAttr(mrec, ATTR_BITMAP)
        if (mb < 0 || mrec[mb + 8].toInt() == 0) return refuse("MFT bitmap is not a regular non-resident attribute")
        val mbs = r.streamOf(r.parseAttrs(mrec).filter { it.type == ATTR_BITMAP }) ?: return refuse("MFT bitmap unreadable")
        mftBitmap = BitFile(mbs, (r.mft?.realSize ?: 0L) / rs)
        return true
    }

    /** Rewrites a non-resident attribute's runlist and sizes inside [rec]; false if the record cannot hold it. */
    private fun setNonResident(rec: ByteArray, ao: Int, runs: List<NtfsReader.Run>?, alloc: Long, real: Long, init: Long): Boolean {
        val rlOff = u16(rec, ao + 0x20)
        val old = u32(rec, ao + 4).toInt()
        val rl = if (runs != null) encodeRuns(runs) else null
        val newLen = if (rl != null) align8(rlOff + rl.size) else old
        val delta = newLen - old
        val used = u32(rec, 0x18).toInt()
        if (used + delta > u32(rec, 0x1C).toInt()) return false
        if (delta != 0) {
            System.arraycopy(rec, ao + old, rec, ao + newLen, used - (ao + old))
            if (delta < 0) java.util.Arrays.fill(rec, used + delta, used, 0)
            p32(rec, ao + 4, newLen.toLong()); p32(rec, 0x18, (used + delta).toLong())
        }
        if (rl != null) { java.util.Arrays.fill(rec, ao + rlOff, ao + newLen, 0); System.arraycopy(rl, 0, rec, ao + rlOff, rl.size); p64(rec, ao + 0x18, runs!!.sumOf { it.len } - 1) }
        p64(rec, ao + 0x28, alloc); p64(rec, ao + 0x30, real); p64(rec, ao + 0x38, init)
        return true
    }

    /** No free MFT record: extend $MFT by 512 records (and its bitmap within its existing allocation). */
    private fun growMft(): Boolean {
        val rec0 = rawRecord(0) ?: return false
        if (findAttr(rec0, ATTR_LIST) >= 0) return refuse("MFT uses an attribute list")
        val attrs = r.parseAttrs(rec0)
        val data = attrs.firstOrNull { it.type == ATTR_DATA && it.name.isEmpty() && it.nonResident } ?: return refuse("MFT data is not regular")
        val bm = attrs.firstOrNull { it.type == ATTR_BITMAP && it.name.isEmpty() && it.nonResident } ?: return refuse("MFT bitmap is not regular")
        val addRecords = 512L
        val addClusters = (addRecords * rs + cs - 1) / cs
        val newRealData = data.realSize + addRecords * rs
        val newBitmapBytes = (((newRealData / rs) + 63) / 64) * 8
        val bmAlloc = bm.runs.sumOf { it.len } * cs
        if (newBitmapBytes > bmAlloc) return refuse("MFT bitmap has no spare room to grow")
        val fresh = allocClusters(addClusters) ?: return refuse("no free space to grow the MFT")
        val runs = data.runs.toMutableList()
        var vcn = runs.sumOf { it.len }
        for (x in fresh) {
            val last = runs.lastOrNull()
            if (last != null && last.lcn >= 0 && last.lcn + last.len == x.lcn) runs[runs.size - 1] = NtfsReader.Run(last.vcn, last.len + x.len, last.lcn)
            else runs.add(NtfsReader.Run(vcn, x.len, x.lcn))
            vcn += x.len
        }
        for (x in fresh) writeSectors(start + x.lcn * cs / 512, ByteArray((x.len * cs).toInt()))          // new records start zeroed
        // zero the bitmap bytes that become valid, so stale cluster contents are not read as "in use"
        val bmStream = r.streamOf(attrs.filter { it.type == ATTR_BITMAP && it.name.isEmpty() }) ?: return false
        val zeroFrom = (bm.initSize / 512) * 512
        val zeroTo = ((newBitmapBytes + 511) / 512) * 512
        if (zeroTo > zeroFrom) {
            val z = ByteArray((zeroTo - zeroFrom).toInt())
            // keep the valid head of the first sector
            val headLen = (bm.initSize - zeroFrom).toInt()
            if (headLen > 0) bmStream.read(zeroFrom, z, 0, headLen)
            writeStream(bmStream, zeroFrom, z)
        }
        val da = findAttr(rec0, ATTR_DATA); val ba = findAttr(rec0, ATTR_BITMAP)
        if (!setNonResident(rec0, da, runs, runs.sumOf { it.len } * cs, newRealData, newRealData)) return refuse("MFT record has no room for the longer runlist")
        val ba2 = findAttr(rec0, ATTR_BITMAP)
        if (!setNonResident(rec0, ba2, null, bmAlloc, maxOf(bm.realSize, newBitmapBytes), maxOf(bm.initSize, newBitmapBytes))) return refuse("cannot update the MFT bitmap size")
        volBitmap!!.flush()
        writeRecord(0, rec0)
        if (!r.reloadMft()) throw WriteFail("MFT unreadable after growing it")
        mftBitmap!!.discard()
        return initMftBitmap()
    }

    // ---------------------------------------------------------------- allocation
    private fun allocRecord(): Long {
        var bf = mftBitmap ?: return -1
        var n = bf.findClear(24)
        if (n < 24) {
            if (!growMft()) return -1
            bf = mftBitmap ?: return -1
            n = bf.findClear(24)
            if (n < 24) return -1
        }
        bf.set(n, true)
        return n
    }

    private fun allocClusters(count: Long): List<NtfsReader.Run>? {
        val bf = volBitmap ?: return null
        val runs = ArrayList<NtfsReader.Run>()
        var need = count; var vcn = 0L; var from = allocHint
        while (need > 0) {
            val s = bf.findClear(from)
            if (s < 0) { for (x in runs) for (c in 0 until x.len) bf.set(x.lcn + c, false); return null }
            var e = s; while (e < r.totalClusters && e - s < need && !bf.get(e)) e++
            for (c in s until e) bf.set(c, true)
            runs.add(NtfsReader.Run(vcn, e - s, s)); vcn += e - s; need -= e - s; from = e
            if (runs.size > MAX_RUNS) { for (x in runs) for (c in 0 until x.len) bf.set(x.lcn + c, false); refuse("free space too fragmented for this file"); return null }
        }
        allocHint = from
        return runs
    }

    private fun freeRuns(runs: List<NtfsReader.Run>) { val bf = volBitmap!!; for (x in runs) if (x.lcn >= 0) for (c in 0 until x.len) bf.set(x.lcn + c, false) }

    private fun encodeRuns(runs: List<NtfsReader.Run>): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var prev = 0L
        for (x in runs) {
            fun size(v: Long, signed: Boolean): Int { var n = 1; while (true) { val lim = 1L shl (8 * n - (if (signed) 1 else 0)); if (if (signed) (v >= -lim && v < lim) else v < lim) return n; n++ } }
            val ls = size(x.len, true); val delta = x.lcn - prev; val os = size(delta, true)   // lengths are read as signed too (0x80 needs two bytes)
            out.write((os shl 4) or ls)
            for (i in 0 until ls) out.write(((x.len ushr (8 * i)) and 0xFF).toInt())
            for (i in 0 until os) out.write(((delta ushr (8 * i)) and 0xFF).toInt())
            prev = x.lcn
        }
        out.write(0)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- names and index
    private fun up(c: Char) = upcase[c.code]
    private fun cmpNames(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) { val x = up(a[i]); val y = up(b[i]); if (x != y) return x.code - y.code }
        return a.length - b.length
    }
    private fun validName(n: String) = n.isNotEmpty() && n.length <= 255 && n != "." && n != ".." && !n.endsWith(".") && !n.endsWith(" ") &&
        n.none { it.code < 32 || it in "\\/:*?\"<>|" }

    private fun fileNameValue(parentRef: Long, name: String, ft: Long, alloc: Long, real: Long, flags: Long): ByteArray {
        val b = ByteArray(0x42 + 2 * name.length)
        p64(b, 0, parentRef); p64(b, 8, ft); p64(b, 16, ft); p64(b, 24, ft); p64(b, 32, ft)
        p64(b, 40, alloc); p64(b, 48, real); p32(b, 56, flags)
        b[0x40] = name.length.toByte(); b[0x41] = 1                 // Win32 namespace
        System.arraycopy(name.toByteArray(Charsets.UTF_16LE), 0, b, 0x42, 2 * name.length)
        return b
    }

    private fun indexEntry(childRef: Long, key: ByteArray): ByteArray {
        val len = align8(16 + key.size)
        val e = ByteArray(len)
        p64(e, 0, childRef); p16(e, 8, len); p16(e, 10, key.size); p16(e, 12, 0)
        System.arraycopy(key, 0, e, 16, key.size)
        return e
    }

    private fun entryName(b: ByteArray, p: Int): String { val k = p + 16; val n = b[k + 0x40].toInt() and 0xFF; return String(b, k + 0x42, n * 2, Charsets.UTF_16LE) }

    /** A located index node: the buffer holding it, where its entries start / end, and how to save it. */
    private inner class Node(val buf: ByteArray, val hdr: Int, val dir: Long, val vcn: Long, val isRoot: Boolean) {
        val first get() = hdr + u32(buf, hdr).toInt()
        var end get() = hdr + u32(buf, hdr + 4).toInt(); set(v) { p32(buf, hdr + 4, (v - hdr).toLong()) }
        val allocEnd get() = hdr + u32(buf, hdr + 8).toInt()
        val isLeaf get() = u32(buf, hdr + 12) and 1L == 0L
    }

    private class DirCtx(val rec: ByteArray, val rootAttr: Int, val blockSize: Int, var alloc: NtfsReader.Stream?)

    private fun dirCtx(dir: Long): DirCtx? {
        val rec = rawRecord(dir) ?: return null
        if (u16(rec, 0x16) and 2 == 0) return null
        val ra = findAttr(rec, ATTR_INDEX_ROOT, "\$I30"); if (ra < 0) return null
        val vo = ra + u16(rec, ra + 0x14)
        val bs = u32(rec, vo + 8).toInt().takeIf { it > 0 } ?: r.idxBlockSize
        val allocAttrs = r.allAttrs(dir)?.filter { it.type == ATTR_INDEX_ALLOC && it.name == "\$I30" }.orEmpty()
        return DirCtx(rec, ra, bs, if (allocAttrs.isEmpty()) null else r.streamOf(allocAttrs))
    }

    private fun rootNode(c: DirCtx, dir: Long) = Node(c.rec, c.rootAttr + u16(c.rec, c.rootAttr + 0x14) + 16, dir, -1, true)

    private fun loadBlock(c: DirCtx, dir: Long, vcn: Long): Node? {
        val a = c.alloc ?: return null
        val unit = if (c.blockSize >= cs) cs else 512
        val b = ByteArray(c.blockSize)
        if (a.read(vcn * unit, b, 0, c.blockSize) != c.blockSize) return null
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "INDX" || !r.applyFixups(b, u16(b, 4), u16(b, 6))) return null
        return Node(b, 0x18, dir, vcn, false)
    }

    private fun saveBlock(c: DirCtx, n: Node) {
        val a = c.alloc ?: throw WriteFail("no index allocation")
        val unit = if (c.blockSize >= cs) cs else 512
        val out = n.buf.copyOf(); applyWriteFixups(out)
        writeStream(a, n.vcn * unit, out)
    }

    /** Result of looking [name] up in a directory index. */
    private class Found(val node: Node, val pos: Int, val entryLen: Int, val leaf: Boolean, val exact: Boolean)

    /** Walks the B+tree: the exact match for [name], or else the leaf position where it would be inserted. */
    private fun locate(c: DirCtx, dir: Long, name: String): Found? {
        var node = rootNode(c, dir)
        var depth = 0
        while (depth++ < 32) {
            var p = node.first
            var hit: Int = -1
            while (p + 16 <= node.end) {
                val flags = u16(node.buf, p + 12); val len = u16(node.buf, p + 8)
                if (len < 16) return null
                if (flags and 2 != 0) { hit = p; break }                      // last entry: everything left was smaller
                val cmp = cmpNames(entryName(node.buf, p), name)
                if (cmp == 0) return Found(node, p, len, node.isLeaf, true)
                if (cmp > 0) { hit = p; break }
                p += len
            }
            if (hit < 0) return null
            if (node.isLeaf) return Found(node, hit, u16(node.buf, hit + 8), true, false)
            val vcn = u64(node.buf, hit + u16(node.buf, hit + 8) - 8)
            node = loadBlock(c, dir, vcn) ?: return null
        }
        return null
    }

    private fun containsName(dir: Long, name: String): Boolean {
        val c = dirCtx(dir) ?: return true
        return locate(c, dir, name)?.exact ?: true
    }

    // ---- B+tree insertion (root conversion + block splits) ----
    private fun isEnd(e: ByteArray) = u16(e, 12) and 2 != 0
    private fun hasSub(e: ByteArray) = u16(e, 12) and 1 != 0
    private fun subOf(e: ByteArray) = u64(e, e.size - 8)
    private fun withSub(e: ByteArray, vcn: Long): ByteArray {
        val base = if (hasSub(e)) e.size - 8 else e.size
        val out = ByteArray(base + 8); System.arraycopy(e, 0, out, 0, base)
        p16(out, 8, base + 8); p16(out, 12, u16(e, 12) or 1); p64(out, base, vcn)
        return out
    }
    private fun withoutSub(e: ByteArray): ByteArray {
        if (!hasSub(e)) return e
        val out = e.copyOf(e.size - 8); p16(out, 8, e.size - 8); p16(out, 12, u16(e, 12) and 1.inv()); return out
    }
    private fun endEntry(sub: Long?): ByteArray { val e = ByteArray(if (sub == null) 16 else 24); p16(e, 8, e.size); p16(e, 12, if (sub == null) 2 else 3); if (sub != null) p64(e, 16, sub); return e }

    private fun readEntries(buf: ByteArray, first: Int, end: Int): MutableList<ByteArray> {
        val out = ArrayList<ByteArray>(); var p = first
        while (p + 16 <= end) { val len = u16(buf, p + 8); if (len < 16) break; out.add(buf.copyOfRange(p, p + len)); if (u16(buf, p + 12) and 2 != 0) break; p += len }
        return out
    }

    private class Level(val isRoot: Boolean, val vcn: Long, val entries: MutableList<ByteArray>, var pos: Int, val leaf: Boolean)

    private fun descend(c: DirCtx, dir: Long, name: String): MutableList<Level>? {
        val path = ArrayList<Level>()
        var rn: Node? = rootNode(c, dir); var node: Node = rn!!
        var depth = 0
        while (depth++ < 32) {
            val entries = readEntries(node.buf, node.first, node.end)
            var pos = entries.size - 1
            for ((i, e) in entries.withIndex()) { if (isEnd(e) || cmpNames(entryName(e, 0), name) > 0) { pos = i; break } }
            path.add(Level(node.isRoot, node.vcn, entries, pos, node.isLeaf))
            if (node.isLeaf) return path
            node = loadBlock(c, dir, subOf(entries[pos])) ?: return null
        }
        return null
    }

    private fun storeRoot(c: DirCtx, dir: Long, entries: List<ByteArray>): Boolean {
        val total = 16 + entries.sumOf { it.size }
        if (!resizeResident(c.rec, c.rootAttr, 16 + total)) return false
        val nh = c.rootAttr + u16(c.rec, c.rootAttr + 0x14) + 16
        p32(c.rec, nh, 16); p32(c.rec, nh + 4, total.toLong()); p32(c.rec, nh + 8, total.toLong()); p32(c.rec, nh + 12, if (entries.any { hasSub(it) }) 1 else 0)
        var o = nh + 16; for (e in entries) { System.arraycopy(e, 0, c.rec, o, e.size); o += e.size }
        writeRecord(dir, c.rec); r.invalidate()
        return true
    }

    private fun blockBytes(c: DirCtx, vcn: Long, entries: List<ByteArray>, leaf: Boolean): ByteArray? {
        val bs = c.blockSize
        val total = 0x28 + entries.sumOf { it.size }
        if (0x18 + total > bs) return null
        val b = ByteArray(bs)
        b[0] = 'I'.code.toByte(); b[1] = 'N'.code.toByte(); b[2] = 'D'.code.toByte(); b[3] = 'X'.code.toByte()
        p16(b, 4, 0x28); p16(b, 6, bs / 512 + 1); p64(b, 0x10, vcn)
        p32(b, 0x18, 0x28); p32(b, 0x1C, total.toLong()); p32(b, 0x20, (bs - 0x18).toLong()); p32(b, 0x24, if (leaf) 0 else 1)
        var o = 0x40; for (e in entries) { System.arraycopy(e, 0, b, o, e.size); o += e.size }
        return b
    }

    private fun storeBlock(c: DirCtx, vcn: Long, entries: List<ByteArray>, leaf: Boolean): Boolean {
        val b = blockBytes(c, vcn, entries, leaf) ?: return false
        val unit = if (c.blockSize >= cs) cs else 512
        applyWriteFixups(b)
        writeStream(c.alloc ?: throw WriteFail("no index allocation"), vcn * unit, b)
        return true
    }

    /** An index block that has been reserved (clusters taken, zeroed) but not yet recorded in the directory's MFT record. */
    private class Pending(val vcn: Long, val runs: List<NtfsReader.Run>, val blocks: Int, val bitmap: ByteArray)

    private fun prepareBlock(c: DirCtx): Pending {
        val attrs = r.parseAttrs(c.rec)
        val old = attrs.firstOrNull { it.type == ATTR_INDEX_ALLOC && it.name == "\$I30" }
        val bmOld = attrs.firstOrNull { it.type == ATTR_BITMAP && it.name == "\$I30" }
        val runs: MutableList<NtfsReader.Run> = old?.runs?.toMutableList() ?: ArrayList()
        val blocks = if (old != null) (old.realSize / c.blockSize).toInt() else 0
        var bitmap = bmOld?.resident?.copyOf() ?: ByteArray(8)
        for (i in 0 until blocks) if ((bitmap[i / 8].toInt() shr (i % 8)) and 1 == 0) {          // reuse a freed block
            bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
            return Pending(i.toLong(), runs, blocks, bitmap)
        }
        val perBlock = ((c.blockSize + cs - 1) / cs).toLong()
        val fresh = allocClusters(perBlock) ?: throw WriteFail("no free space for an index block")
        var vcn = runs.sumOf { it.len }
        for (x in fresh) {
            val last = runs.lastOrNull()
            if (last != null && last.lcn >= 0 && last.lcn + last.len == x.lcn) runs[runs.size - 1] = NtfsReader.Run(last.vcn, last.len + x.len, last.lcn)
            else runs.add(NtfsReader.Run(vcn, x.len, x.lcn))
            vcn += x.len
        }
        val id = blocks
        if (id + 1 > bitmap.size * 8) bitmap = bitmap.copyOf(bitmap.size + 8)
        bitmap[id / 8] = (bitmap[id / 8].toInt() or (1 shl (id % 8))).toByte()
        for (x in fresh) writeSectors(start + x.lcn * cs / 512, ByteArray((x.len * cs).toInt()))     // never leave stale data looking like a node
        volBitmap!!.flush()
        return Pending(id.toLong(), runs, blocks + 1, bitmap)
    }

    /** Writes a block's bytes straight to the clusters it will occupy (the attribute that maps them is committed afterwards). */
    private fun writeBlockPhysical(c: DirCtx, p: Pending, entries: List<ByteArray>, leaf: Boolean): Boolean {
        val b = blockBytes(c, p.vcn, entries, leaf) ?: return false
        applyWriteFixups(b)
        val unit = if (c.blockSize >= cs) cs else 512
        var off = p.vcn * unit; var o = 0
        while (o < b.size) {
            val vcn = off / cs
            val run = p.runs.firstOrNull { vcn >= it.vcn && vcn < it.vcn + it.len } ?: throw WriteFail("block has no clusters")
            val inRun = (run.vcn + run.len) * cs - off
            val n = minOf(inRun, (b.size - o).toLong()).toInt()
            writeSectors(start + ((run.lcn + (vcn - run.vcn)) * cs + off % cs) / 512, b.copyOfRange(o, o + n))
            off += n; o += n
        }
        return true
    }

    /** Records the reserved block in the directory record, optionally replacing the root's entries in the same write. */
    private fun commitBlock(c: DirCtx, dir: Long, p: Pending, newRoot: List<ByteArray>?) {
        val rec = c.rec
        if (newRoot != null) {                                       // shrink the root first so the new attributes find room
            val total = 16 + newRoot.sumOf { it.size }
            if (!resizeResident(rec, c.rootAttr, 16 + total)) throw WriteFail("cannot rewrite the directory root")
            val nh = c.rootAttr + u16(rec, c.rootAttr + 0x14) + 16
            p32(rec, nh, 16); p32(rec, nh + 4, total.toLong()); p32(rec, nh + 8, total.toLong()); p32(rec, nh + 12, if (newRoot.any { hasSub(it) }) 1 else 0)
            var o = nh + 16; for (e in newRoot) { System.arraycopy(e, 0, rec, o, e.size); o += e.size }
        }
        for (a in findAll(rec, ATTR_BITMAP).reversed()) if (String(rec, a + u16(rec, a + 10), 8, Charsets.UTF_16LE) == "\$I30") removeAttr(rec, a)
        for (a in findAll(rec, ATTR_INDEX_ALLOC).reversed()) removeAttr(rec, a)
        val ra = c.rootAttr
        var q = ra + u32(rec, ra + 4).toInt()
        val rl = encodeRuns(p.runs)
        val nameBytes = "\$I30".toByteArray(Charsets.UTF_16LE)
        val allocLen = align8(0x48 + rl.size)
        val bmLen = align8(0x20 + p.bitmap.size)
        val used = u32(rec, 0x18).toInt()
        if (used + allocLen + bmLen > rs) throw WriteFail("directory record has no room for the index allocation attributes")
        System.arraycopy(rec, q, rec, q + allocLen + bmLen, used - q)
        java.util.Arrays.fill(rec, q, q + allocLen + bmLen, 0)
        var id = u16(rec, 0x28)
        p32(rec, q, ATTR_INDEX_ALLOC.toLong()); p32(rec, q + 4, allocLen.toLong()); rec[q + 8] = 1; rec[q + 9] = 4; p16(rec, q + 10, 0x40); p16(rec, q + 14, id++)
        p64(rec, q + 0x10, 0); p64(rec, q + 0x18, p.runs.sumOf { it.len } - 1); p16(rec, q + 0x20, 0x48)
        val sz = p.blocks.toLong() * c.blockSize
        p64(rec, q + 0x28, p.runs.sumOf { it.len } * cs); p64(rec, q + 0x30, sz); p64(rec, q + 0x38, sz)
        System.arraycopy(nameBytes, 0, rec, q + 0x40, nameBytes.size); System.arraycopy(rl, 0, rec, q + 0x48, rl.size)
        q += allocLen
        p32(rec, q, ATTR_BITMAP.toLong()); p32(rec, q + 4, bmLen.toLong()); rec[q + 9] = 4; p16(rec, q + 10, 0x18); p16(rec, q + 14, id++)
        p32(rec, q + 0x10, p.bitmap.size.toLong()); p16(rec, q + 0x14, 0x20)
        System.arraycopy(nameBytes, 0, rec, q + 0x18, nameBytes.size); System.arraycopy(p.bitmap, 0, rec, q + 0x20, p.bitmap.size)
        p16(rec, 0x28, id); p32(rec, 0x18, (used + allocLen + bmLen).toLong())
        volBitmap!!.flush()
        writeRecord(dir, rec); r.invalidate()
        c.alloc = dirCtx(dir)?.alloc ?: throw WriteFail("directory unreadable after growing its index")
    }

    private fun insertEntry(dir: Long, name: String, entry: ByteArray): Boolean {
        val c = dirCtx(dir) ?: return refuse("cannot read directory")
        val path = descend(c, dir, name) ?: return refuse("directory index unreadable")
        if (path.last().entries.any { !isEnd(it) && cmpNames(entryName(it, 0), name) == 0 }) return refuse("name already exists")
        var carry = entry
        var level = path.lastIndex
        while (true) {
            val lv = path[level]
            lv.entries.add(lv.pos, carry)
            val stored = if (lv.isRoot) storeRoot(c, dir, lv.entries) else storeBlock(c, lv.vcn, lv.entries, lv.leaf)
            if (stored) return true
            if (lv.isRoot) {
                // the root outgrew its MFT record: its entries move into a new index block and the root keeps one pointer to it
                val pb = prepareBlock(c)
                if (!writeBlockPhysical(c, pb, lv.entries, lv.leaf)) return refuse("index entries do not fit a block")
                commitBlock(c, dir, pb, listOf(endEntry(pb.vcn)))
                return true
            }
            // split this block: lower half stays, the median key moves up, the upper half goes to a new block
            val all = lv.entries
            val total = all.sumOf { it.size }
            var acc = 0; var m = 0
            while (m < all.size - 2 && acc + all[m].size < total / 2) { acc += all[m].size; m++ }
            val medianSub = if (lv.leaf) null else subOf(all[m])
            val left = ArrayList<ByteArray>(all.subList(0, m)).also { it.add(endEntry(medianSub)) }
            val right = ArrayList<ByteArray>(all.subList(m + 1, all.size))
            val pb = prepareBlock(c); val newVcn = pb.vcn
            if (!writeBlockPhysical(c, pb, right, lv.leaf)) return refuse("split halves do not fit a block")
            commitBlock(c, dir, pb, null)
            if (!storeBlock(c, lv.vcn, left, lv.leaf)) return refuse("split halves do not fit a block")
            val parent = path[level - 1]
            parent.entries[parent.pos] = withSub(parent.entries[parent.pos], newVcn)       // old pointer now leads to the upper half
            carry = withSub(withoutSub(all[m]), lv.vcn)                                   // median key points at the lower half
            level--
        }
    }

    /** Checks that the entry for [name] can be removed, and returns its location. */
    private fun removable(dir: Long, name: String): Pair<DirCtx, Found>? {
        val c = dirCtx(dir) ?: return null
        val f = locate(c, dir, name) ?: return null
        if (!f.exact || !f.leaf) return null                                        // entries in interior nodes carry sub-node pointers
        if (!f.node.isRoot) {                                                       // never leave an index block empty
            var cnt = 0; var p = f.node.first
            while (p + 16 <= f.node.end) { cnt++; if (u16(f.node.buf, p + 12) and 2 != 0) break; p += u16(f.node.buf, p + 8) }
            if (cnt <= 2) return null
        }
        return c to f
    }

    private fun removeEntry(dir: Long, name: String): Boolean {
        val (c, f) = removable(dir, name) ?: return false
        val n = f.node
        if (n.isRoot) {
            val oldEnd = n.end
            System.arraycopy(c.rec, f.pos + f.entryLen, c.rec, f.pos, oldEnd - (f.pos + f.entryLen))
            val newTotal = oldEnd - n.hdr - f.entryLen
            p32(c.rec, n.hdr + 4, newTotal.toLong()); p32(c.rec, n.hdr + 8, newTotal.toLong())
            resizeResident(c.rec, c.rootAttr, newTotal + 16)
            writeRecord(dir, c.rec)
        } else {
            System.arraycopy(n.buf, f.pos + f.entryLen, n.buf, f.pos, n.end - (f.pos + f.entryLen))
            val oldEnd = n.end
            n.end = oldEnd - f.entryLen
            java.util.Arrays.fill(n.buf, n.end, oldEnd, 0)
            saveBlock(c, n)
        }
        return true
    }

    // ---------------------------------------------------------------- record building
    private fun buildRecord(
        recNo: Long, seq: Int, isDir: Boolean, parentRef: Long, name: String, securityId: Long, ft: Long,
        runs: List<NtfsReader.Run>?, size: Long, allocSize: Long, resident: ByteArray?
    ): ByteArray? {
        val rec = ByteArray(rs)
        rec[0] = 'F'.code.toByte(); rec[1] = 'I'.code.toByte(); rec[2] = 'L'.code.toByte(); rec[3] = 'E'.code.toByte()
        val usaCount = rs / 512 + 1
        p16(rec, 4, 0x30); p16(rec, 6, usaCount)
        p16(rec, 0x10, seq); p16(rec, 0x12, 1); p16(rec, 0x16, if (isDir) 3 else 1); p32(rec, 0x1C, rs.toLong())
        p16(rec, 0x28, 3); p32(rec, 0x2C, recNo)
        val attrStart = align8(0x30 + usaCount * 2); p16(rec, 0x14, attrStart)
        var p = attrStart
        var id = 0
        fun header(type: Int, len: Int, nonRes: Boolean, valLen: Int) {
            p32(rec, p, type.toLong()); p32(rec, p + 4, len.toLong()); rec[p + 8] = if (nonRes) 1 else 0; p16(rec, p + 14, id++)
            if (!nonRes) { p32(rec, p + 0x10, valLen.toLong()); p16(rec, p + 0x14, 0x18) }
        }
        // $STANDARD_INFORMATION (NTFS 3.x, 72 bytes)
        header(ATTR_STDINFO, 0x18 + 72, false, 72)
        val si = p + 0x18
        p64(rec, si, ft); p64(rec, si + 8, ft); p64(rec, si + 16, ft); p64(rec, si + 24, ft)
        p32(rec, si + 32, if (isDir) 0x10000000L.and(0) else 0x20); p32(rec, si + 52, securityId)
        p += 0x18 + 72
        // $FILE_NAME
        val fnv = fileNameValue(parentRef, name, ft, allocSize, size, if (isDir) 0x10000000L else 0x20L)
        val fnLen = align8(0x18 + fnv.size)
        header(ATTR_NAME, fnLen, false, fnv.size); rec[p + 0x16] = 1
        System.arraycopy(fnv, 0, rec, p + 0x18, fnv.size)
        p += fnLen
        if (isDir) {
            val entries = 16                                              // just the "last entry" terminator
            val valLen = 16 + 16 + entries
            val nameBytes = "\$I30".toByteArray(Charsets.UTF_16LE)
            val voff = align8(0x18 + nameBytes.size)
            val len = align8(voff + valLen)
            header(ATTR_INDEX_ROOT, len, false, valLen); rec[p + 9] = 4; p16(rec, p + 10, 0x18); p16(rec, p + 0x14, voff)
            System.arraycopy(nameBytes, 0, rec, p + 0x18, nameBytes.size)
            val v = p + voff
            p32(rec, v, ATTR_NAME.toLong()); p32(rec, v + 4, 1); p32(rec, v + 8, r.idxBlockSize.toLong()); rec[v + 12] = (if (r.idxBlockSize >= cs) r.idxBlockSize / cs else r.idxBlockSize / 512).toByte()
            p32(rec, v + 16, 16); p32(rec, v + 20, (16 + entries).toLong()); p32(rec, v + 24, (16 + entries).toLong()); p32(rec, v + 28, 0)
            val e = v + 32
            p16(rec, e + 8, 16); p16(rec, e + 12, 2)
            p += len
        } else if (resident != null) {
            val len = align8(0x18 + resident.size)
            header(ATTR_DATA, len, false, resident.size)
            System.arraycopy(resident, 0, rec, p + 0x18, resident.size)
            p += len
        } else {
            val rl = encodeRuns(runs!!)
            val len = align8(0x40 + rl.size)
            header(ATTR_DATA, len, true, 0); p16(rec, p + 10, 0x40)
            p64(rec, p + 0x10, 0); p64(rec, p + 0x18, runs.sumOf { it.len } - 1); p16(rec, p + 0x20, 0x40)
            p64(rec, p + 0x28, allocSize); p64(rec, p + 0x30, size); p64(rec, p + 0x38, size)
            System.arraycopy(rl, 0, rec, p + 0x40, rl.size)
            p += len
        }
        if (p + 8 > rs) return null
        p32(rec, p, 0xFFFFFFFFL); p += 8
        p32(rec, 0x18, p.toLong())
        return rec
    }

    private fun refOf(rec: Long, seq: Int) = rec or (seq.toLong() shl 48)

    // ---------------------------------------------------------------- public API
    private inline fun guarded(what: String, body: () -> Boolean): Boolean = synchronized(this) {
        lastRefusal = null
        try {
            if (!preflight()) return false
            val ok = body()
            if (ok) dev.flushCache()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "NTFS $what failed", e); lastRefusal = e.message
            volBitmap?.discard(); mftBitmap?.discard(); checked = null
            false
        } finally { r.invalidate() }
    }

    private fun parentInfo(dir: Long): Pair<Long, Long>? {       // (reference with sequence, security id)
        val rec = rawRecord(dir) ?: return null
        val si = findAttr(rec, ATTR_STDINFO)
        val sec = if (si >= 0) u32(rec, si + u16(rec, si + 0x14) + 52) else 0x100L
        return refOf(dir, u16(rec, 0x10)) to (if (sec == 0L) 0x100L else sec)
    }

    private fun touchDir(dir: Long, ft: Long) {
        val rec = rawRecord(dir) ?: return
        val si = findAttr(rec, ATTR_STDINFO); if (si < 0) return
        val v = si + u16(rec, si + 0x14)
        p64(rec, v + 8, ft); p64(rec, v + 16, ft); p64(rec, v + 24, ft)
        writeRecord(dir, rec)
    }

    private fun create(parent: FileSystemEntry, name: String, isDir: Boolean, size: Long, input: InputStream?, onProgress: ((Long) -> Unit)?): Boolean {
        if (!validName(name)) return refuse("invalid file name")
        val dirNo = parent.inodeOid
        val (pref, sec) = parentInfo(dirNo) ?: return refuse("cannot read parent directory")
        if (containsName(dirNo, name)) return refuse("a file with this name already exists")
        val recNo = allocRecord(); if (recNo < 0) return refuse("MFT has no free record")
        val old = rawRecord(recNo)
        val seq = if (old != null) ((u16(old, 0x10) + 1) and 0xFFFF).let { if (it == 0) 1 else it } else 1
        val ft = nowFt()
        var runs: List<NtfsReader.Run>? = null
        var resident: ByteArray? = null
        val allocSize: Long
        if (!isDir) {
            if (size <= 640) {
                resident = ByteArray(size.toInt()).also { var o = 0; while (o < it.size) { val n = input!!.read(it, o, it.size - o); if (n <= 0) break; o += n } }
                allocSize = 0
            } else {
                val clusters = (size + cs - 1) / cs
                runs = allocClusters(clusters) ?: run { mftBitmap!!.set(recNo, false); return refuse(lastRefusal ?: "not enough free space") }
                allocSize = clusters * cs
                writeData(runs, size, input!!, onProgress)
            }
        } else allocSize = 0
        val rec = buildRecord(recNo, seq, isDir, pref, name, sec, ft, runs, size, allocSize, resident)
        if (rec == null) { runs?.let { freeRuns(it) }; mftBitmap!!.set(recNo, false); return refuse("file name too long for an MFT record") }
        // visible only once everything it needs is on disk: bitmaps, record, then the directory entry
        volBitmap!!.flush(); mftBitmap!!.flush()
        writeRecord(recNo, rec)
        val key = fileNameValue(pref, name, ft, allocSize, if (isDir) 0 else size, if (isDir) 0x10000000L else 0x20L)
        if (!insertEntry(dirNo, name, indexEntry(refOf(recNo, seq), key))) {
            val dead = rec.copyOf(); p16(dead, 0x16, 0); writeRecord(recNo, dead)
            runs?.let { freeRuns(it) }; mftBitmap!!.set(recNo, false); volBitmap!!.flush(); mftBitmap!!.flush()
            return false
        }
        touchDir(dirNo, ft)
        return true
    }

    private fun writeData(runs: List<NtfsReader.Run>, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?) {
        var done = 0L
        val buf = ByteArray(1 shl 20)
        for (x in runs) {
            var left = x.len * cs
            var lba = start + x.lcn * cs / 512
            while (left > 0) {
                val want = minOf(buf.size.toLong(), left).toInt()
                var got = 0
                val avail = minOf(want.toLong(), size - done).coerceAtLeast(0).toInt()
                while (got < avail) { val n = input.read(buf, got, avail - got); if (n <= 0) break; got += n }
                java.util.Arrays.fill(buf, got, want, 0)
                writeSectors(lba, buf.copyOf(want)); done += got; lba += want / 512; left -= want
                onProgress?.invoke(done)
            }
        }
        if (done < size) throw WriteFail("input ended early ($done of $size bytes)")
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String) = guarded("mkdir $name") { create(parentEntry, name, true, 0, null, null) }

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray) =
        guarded("write $name") { create(parentEntry, name, false, data.size.toLong(), data.inputStream(), null) }

    override fun writeFileStream(parentEntry: FileSystemEntry, name: String, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?) =
        guarded("write $name") { create(parentEntry, name, false, size, input, onProgress) }

    override fun deleteEntry(entry: FileSystemEntry): Boolean = guarded("delete ${entry.name}") { delete(entry.inodeOid, entry.path) }

    private fun delete(recNo: Long, path: String): Boolean {
        if (recNo < 24) return refuse("system file")
        val rec = rawRecord(recNo) ?: return refuse("cannot read record")
        val flags = u16(rec, 0x16)
        if (flags and 1 == 0) return refuse("record not in use")
        if (u64(rec, 0x20) != 0L || u16(rec, 0x12) != 1 || findAttr(rec, ATTR_LIST) >= 0) return refuse("file uses hard links or extension records")
        if (findAll(rec, ATTR_DATA).any { (u16(rec, it + 12) and 0x4001) != 0 }) return refuse("compressed or encrypted file")
        val isDir = flags and 2 != 0
        if (isDir) {
            for (child in r.listDirectory(0, path)) if (!delete(child.inodeOid, child.path)) return false
        }
        // every $FILE_NAME of the record has an entry in some directory index
        val names = findAll(rec, ATTR_NAME).map { a -> val v = a + u16(rec, a + 0x14); (u64(rec, v) and 0xFFFFFFFFFFFFL) to String(rec, v + 0x42, (rec[v + 0x40].toInt() and 0xFF) * 2, Charsets.UTF_16LE) }
        for ((parent, nm) in names) if (removable(parent, nm) == null) return refuse("cannot remove \"$nm\" from its directory safely (interior or last entry of an index block)")
        for ((parent, nm) in names) if (!removeEntry(parent, nm)) return false
        // free every cluster the record owns, then retire the record
        var p = u16(rec, 0x14)
        while (p + 8 <= rec.size) {
            val t = u32(rec, p).toInt(); if (t == -1) break
            val len = u32(rec, p + 4).toInt(); if (len <= 0) break
            if (rec[p + 8].toInt() != 0) freeRuns(r.decodeRuns(rec, p + u16(rec, p + 0x20), p + len, u64(rec, p + 0x10)))
            p += len
        }
        p16(rec, 0x16, 0); p16(rec, 0x10, (u16(rec, 0x10) + 1) and 0xFFFF)
        writeRecord(recNo, rec)
        mftBitmap!!.set(recNo, false)
        volBitmap!!.flush(); mftBitmap!!.flush()
        names.firstOrNull()?.let { touchDir(it.first, nowFt()) }
        return true
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean = guarded("rename ${entry.name}") {
        if (!validName(newName)) return@guarded refuse("invalid file name")
        val recNo = entry.inodeOid
        val rec = rawRecord(recNo) ?: return@guarded refuse("cannot read record")
        if (u64(rec, 0x20) != 0L || u16(rec, 0x12) != 1 || findAttr(rec, ATTR_LIST) >= 0) return@guarded refuse("file uses hard links or extension records")
        val fns = findAll(rec, ATTR_NAME)
        if (fns.isEmpty()) return@guarded refuse("no file name attribute")
        val v0 = fns[0] + u16(rec, fns[0] + 0x14)
        val parent = u64(rec, v0) and 0xFFFFFFFFFFFFL
        val parentRef = u64(rec, v0)
        if (cmpNames(entry.name, newName) != 0 && containsName(parent, newName)) return@guarded refuse("a file with this name already exists")
        val oldNames = fns.map { a -> val v = a + u16(rec, a + 0x14); String(rec, v + 0x42, (rec[v + 0x40].toInt() and 0xFF) * 2, Charsets.UTF_16LE) }
        for (nm in oldNames) if (removable(parent, nm) == null) return@guarded refuse("cannot change this entry's directory index safely")
        val ft = nowFt()
        // replace the record's name attribute(s) by a single Win32 name
        val old = v0
        val times = ByteArray(32); System.arraycopy(rec, old + 8, times, 0, 32)
        val alloc = u64(rec, old + 40); val real = u64(rec, old + 48); val fl = u32(rec, old + 56)
        for (a in fns.reversed()) removeAttr(rec, a)
        val key = fileNameValue(parentRef, newName, ft, alloc, real, fl)
        System.arraycopy(times, 0, key, 8, 32)
        val ao = run { var p = u16(rec, 0x14); var at = p; while (p + 8 <= rec.size) { val t = u32(rec, p).toInt(); if (t == -1 || t > ATTR_NAME) { at = p; break }; at = p + u32(rec, p + 4).toInt(); p = at }; at }
        val len = align8(0x18 + key.size)
        val used = u32(rec, 0x18).toInt()
        if (used + len > rs) return@guarded refuse("new name does not fit in the MFT record")
        System.arraycopy(rec, ao, rec, ao + len, used - ao)
        java.util.Arrays.fill(rec, ao, ao + len, 0)
        p32(rec, ao, ATTR_NAME.toLong()); p32(rec, ao + 4, len.toLong()); p16(rec, ao + 14, u16(rec, 0x28)); p32(rec, ao + 0x10, key.size.toLong()); p16(rec, ao + 0x14, 0x18); rec[ao + 0x16] = 1
        System.arraycopy(key, 0, rec, ao + 0x18, key.size)
        p16(rec, 0x28, u16(rec, 0x28) + 1); p32(rec, 0x18, (used + len).toLong())
        for (nm in oldNames) if (!removeEntry(parent, nm)) return@guarded false
        writeRecord(recNo, rec)
        if (!insertEntry(parent, newName, indexEntry(refOf(recNo, u16(rec, 0x10)), key))) return@guarded false
        touchDir(parent, ft)
        true
    }
}
