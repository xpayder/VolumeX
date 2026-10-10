package app.feldkit.storage.filesystem.apfs

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemWriter
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * EXPERIMENTAL APFS writer (create / write / rename / delete) that edits the current checkpoint's
 * metadata **in place** instead of producing a new checkpoint.
 *
 * It only works on volumes that are: unencrypted, not sealed, without snapshots, on a non-Fusion
 * container whose chunk bitmaps already exist for the space it allocates from. Each operation builds
 * all its changes in memory (fs-tree and extent-ref nodes, volume object map entries, space manager
 * chunk bitmaps, volume and container counters) and writes them in one go at the end.
 *
 * Validated against `fsck_apfs` on images made by macOS (see WriteFixtureTest / tools/verify-written.sh).
 * Because it is not copy-on-write, a power loss *during* the final write burst can leave the volume
 * needing repair on the Mac; keep a backup of anything important.
 */
class ApfsWriter(
    private val dev: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val volumeIndex: () -> Int = { 0 }
) : FileSystemWriter {

    companion object {
        private const val TAG = "FeldKit"
        private const val T_INODE = 3L
        private const val T_XATTR = 4L
        private const val T_DSTREAM_ID = 6L
        private const val T_EXTENT_FILE = 8L
        private const val T_DREC = 9L
        private const val T_PHYS_EXTENT = 2L
        private const val MASK60 = (1L shl 60) - 1
        private const val INC_CASE_INSENSITIVE = 0x1L
        private const val INC_SEALED = 0x10L
    }

    private class Fail(msg: String) : Exception(msg)
    private class Entry(val key: ByteArray, var value: ByteArray)
    private class Info(var flags: Int, var level: Int, var toff: Int, var tlen: Int)
    private class Step(val paddr: Long, val idx: Int)
    private class Loc(val path: List<Step>, val leaf: Long, val pos: Int, val found: Boolean)
    private class Run(val start: Long, val count: Long)

    // ── little-endian helpers ────────────────────────────────────────────────
    private fun r16(a: ByteArray, o: Int) = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
    private fun r32(a: ByteArray, o: Int): Long = (r16(a, o).toLong()) or (r16(a, o + 2).toLong() shl 16)
    private fun r64(a: ByteArray, o: Int): Long = r32(a, o) or (r32(a, o + 4) shl 32)
    private fun w16(a: ByteArray, o: Int, v: Int) { a[o] = v.toByte(); a[o + 1] = (v shr 8).toByte() }
    private fun w32(a: ByteArray, o: Int, v: Long) { for (k in 0..3) a[o + k] = (v shr (8 * k)).toByte() }
    private fun w64(a: ByteArray, o: Int, v: Long) { for (k in 0..7) a[o + k] = (v shr (8 * k)).toByte() }
    private fun le64(v: Long) = ByteArray(8).also { w64(it, 0, v) }
    private fun le32(v: Long) = ByteArray(4).also { w32(it, 0, v) }

    // ── public API ───────────────────────────────────────────────────────────
    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean =
        writeFileStream(parentEntry, name, data.size.toLong(), java.io.ByteArrayInputStream(data), null)

    override fun writeFileStream(parentEntry: FileSystemEntry, name: String, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?): Boolean =
        run("writeFile $name") { tx -> tx.createEntry(parentEntry.inodeOid, name, false, size, input, onProgress) }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean =
        run("createDirectory $name") { tx -> tx.createEntry(parentEntry.inodeOid, name, true, 0, null, null) }

    override fun deleteEntry(entry: FileSystemEntry): Boolean =
        run("deleteEntry ${entry.name}") { tx -> tx.deleteEntry(entry.parentOid, entry.name, entry.inodeOid) }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean =
        run("renameEntry ${entry.name}") { tx -> tx.rename(entry.parentOid, entry.name, entry.inodeOid, newName) }

    private fun run(what: String, block: (Tx) -> Unit): Boolean = try {
        val tx = Tx()
        block(tx)
        tx.commit()
        Log.i(TAG, "APFS $what OK"); true
    } catch (e: Fail) {
        Log.w(TAG, "APFS $what refused: ${e.message}"); false
    } catch (e: Exception) {
        Log.e(TAG, "APFS $what failed", e); false
    }

    // ── checksum ─────────────────────────────────────────────────────────────
    private fun fletcher(b: ByteArray): Long {
        val mod = 0xFFFFFFFFL; var s1 = 0L; var s2 = 0L; var i = 8
        while (i < b.size) { s1 = (s1 + r32(b, i)) % mod; s2 = (s2 + s1) % mod; i += 4 }
        val c1 = mod - ((s1 + s2) % mod); val c2 = mod - ((s1 + c1) % mod)
        return (c2 shl 32) or c1
    }

    // ── key handling ─────────────────────────────────────────────────────────
    private fun idOf(k: ByteArray) = r64(k, 0) and MASK60
    private fun typeOf(k: ByteArray) = (r64(k, 0) ushr 60) and 0xF
    private fun hdrKey(oid: Long, type: Long) = le64((type shl 60) or oid)

    private fun crc32c(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0x82F63B78L else crc ushr 1 }
        }
        return crc and 0xFFFFFFFFL
    }

    private inner class Tx {
        // ---- block store ----
        val bs: Int
        private val spb: Int
        private val cache = HashMap<Long, ByteArray>()
        private val dirty = LinkedHashSet<Long>()
        private val rawBlocks = HashSet<Long>()
        fun lba(paddr: Long) = partitionStartLba + paddr * spb
        fun blk(paddr: Long): ByteArray = cache.getOrPut(paddr) { dev.readSectors(lba(paddr), spb) ?: throw Fail("cannot read block $paddr") }
        fun touch(paddr: Long) { dirty.add(paddr) }

        // ---- container / volume state ----
        private val nx: ByteArray
        private val nxPaddr: Long
        private val nxXid: Long
        private var nextOid: Long
        private val smPaddr: Long
        val vsbPaddr: Long
        val vsb: ByteArray
        val volXid: Long
        val caseInsensitive: Boolean
        private var volOmapRoot: Long = 0L
        val fsRootOid: Long
        val extRoot: Long
        private val now: Long = System.currentTimeMillis() * 1_000_000L

        // trees
        private lateinit var omap: BTree
        lateinit var fs: BTree
        lateinit var ext: BTree

        init {
            val s0 = dev.readSectors(partitionStartLba, 8) ?: throw Fail("cannot read container")
            if (r32(s0, 32) != 0x4253584EL) throw Fail("not an APFS container")
            bs = r32(s0, 36).toInt(); spb = bs / 512
            val b0 = blk(0)
            // newest valid container superblock from the checkpoint descriptor area (as the reader does)
            var best = b0; var bestPaddr = 0L
            val descBase = r64(b0, 112); val descBlocks = r32(b0, 104).toInt()
            if (descBase < 0) throw Fail("non-contiguous checkpoint area is not supported")
            for (i in 0 until descBlocks) {
                val c = blk(descBase + i)
                if (r32(c, 32) == 0x4253584EL && r64(c, 16) >= r64(best, 16) && fletcher(c) == r64(c, 0)) { best = c; bestPaddr = descBase + i }
            }
            nx = best; nxPaddr = bestPaddr; nxXid = r64(nx, 16); nextOid = r64(nx, 88)
            val smOid = r64(nx, 152)
            // spaceman location from the checkpoint map of the same xid
            var sm = -1L
            for (i in 0 until descBlocks) {
                val c = blk(descBase + i)
                if ((r32(c, 24) and 0xFFFF) == 12L && r64(c, 16) == nxXid) {
                    val cnt = r32(c, 36).toInt()
                    for (k in 0 until cnt) {
                        val o = 40 + 40 * k
                        if (r64(c, o + 24) == smOid) sm = r64(c, o + 32)
                    }
                }
            }
            if (sm < 0) throw Fail("space manager not found in the checkpoint")
            smPaddr = sm
            // container omap -> volume superblock
            val contOmapPhys = blk(r64(nx, 160))
            val contOmap = BTree(r64(contOmapPhys, 48), fixed = true, virtualChildren = false) { a, b -> cmpOmap(a, b) }
            val vols = (0 until r32(nx, 180).toInt()).map { r64(nx, 184 + 8 * it) }.filter { it != 0L }
            val vIdx = volumeIndex()
            if (vIdx !in vols.indices) throw Fail("volume $vIdx not found")
            vsbPaddr = contOmap.omapLookup(vols[vIdx], Long.MAX_VALUE) ?: throw Fail("volume superblock not mapped")
            vsb = blk(vsbPaddr)
            if (r32(vsb, 32) != 0x42535041L) throw Fail("bad volume superblock")
            volXid = r64(vsb, 16)
            val incompat = r64(vsb, 56)
            caseInsensitive = incompat and INC_CASE_INSENSITIVE != 0L
            if (incompat and INC_SEALED != 0L) throw Fail("sealed (system) volume")
            if (r64(vsb, 264) and 1L == 0L) throw Fail("encrypted volumes are not supported for writing")
            if (r64(vsb, 216) != 0L) throw Fail("volume has snapshots - delete them on the Mac first")
            if (r64(blk(smPaddr), 48 + 0) == 0L) throw Fail("space manager unreadable")
            if (r32(blk(smPaddr), 88) != 0L || r64(blk(smPaddr), 56 + 8 + 0) < 0) throw Fail("unsupported space manager layout")
            val volOmapPhys = blk(r64(vsb, 128))
            volOmapRoot = r64(volOmapPhys, 48)
            fsRootOid = r64(vsb, 136); extRoot = r64(vsb, 144)
            omap = BTree(volOmapRoot, fixed = true, virtualChildren = false) { a, b -> cmpOmap(a, b) }
            val fsRootPaddr = omap.omapLookup(fsRootOid, volXid) ?: throw Fail("fs tree root not mapped")
            fs = BTree(fsRootPaddr, fixed = false, virtualChildren = true) { a, b -> cmpFs(a, b) }
            ext = BTree(extRoot, fixed = false, virtualChildren = false) { a, b -> cmpExt(a, b) }
        }

        // ---- key comparators ----
        private fun cmpOmap(a: ByteArray, b: ByteArray): Int {
            val oa = r64(a, 0); val ob = r64(b, 0)
            if (oa != ob) return java.lang.Long.compareUnsigned(oa, ob)
            return java.lang.Long.compareUnsigned(r64(a, 8), r64(b, 8))
        }
        private fun cmpExt(a: ByteArray, b: ByteArray) = java.lang.Long.compareUnsigned(idOf(a), idOf(b))
        private fun cmpFs(a: ByteArray, b: ByteArray): Int {
            val ia = idOf(a); val ib = idOf(b)
            if (ia != ib) return java.lang.Long.compareUnsigned(ia, ib)
            val ta = typeOf(a); val tb = typeOf(b)
            if (ta != tb) return ta.compareTo(tb)
            return when (ta) {
                T_DREC -> {
                    val na = r32(a, 8); val nb = r32(b, 8)
                    if (na != nb) na.compareTo(nb) else compareBytes(a, 12, b, 12)
                }
                T_EXTENT_FILE -> java.lang.Long.compareUnsigned(r64(a, 8), r64(b, 8))
                T_XATTR -> compareBytes(a, 10, b, 10)
                else -> 0
            }
        }
        private fun compareBytes(a: ByteArray, ao: Int, b: ByteArray, bo: Int): Int {
            val la = a.size - ao; val lb = b.size - bo; val n = minOf(la, lb)
            for (i in 0 until n) { val x = a[ao + i].toInt() and 0xFF; val y = b[bo + i].toInt() and 0xFF; if (x != y) return x.compareTo(y) }
            return la.compareTo(lb)
        }

        // ---- node codec ----
        fun decode(b: ByteArray, fixed: Boolean): Pair<Info, MutableList<Entry>> {
            val flags = r16(b, 32); val level = r16(b, 34); val n = r32(b, 36).toInt(); val toff = r16(b, 40); val tlen = r16(b, 42)
            val root = flags and 1 != 0
            val vend = bs - (if (root) 40 else 0); val kstart = 56 + toff + tlen
            val ents = ArrayList<Entry>(n)
            for (i in 0 until n) {
                if (fixed) {
                    val ko = r16(b, 56 + toff + 4 * i); val vo = r16(b, 58 + toff + 4 * i)
                    val vs = if (level == 0) 16 else 8
                    ents.add(Entry(b.copyOfRange(kstart + ko, kstart + ko + 16), b.copyOfRange(vend - vo, vend - vo + vs)))
                } else {
                    val t = 56 + toff + 8 * i
                    val ko = r16(b, t); val kl = r16(b, t + 2); val vo = r16(b, t + 4); val vl = r16(b, t + 6)
                    ents.add(Entry(b.copyOfRange(kstart + ko, kstart + ko + kl), if (vl == 0) ByteArray(0) else b.copyOfRange(vend - vo, vend - vo + vl)))
                }
            }
            return Info(flags, level, toff, tlen) to ents
        }

        fun encode(b: ByteArray, info: Info, ents: List<Entry>, fixed: Boolean): Boolean {
            val root = info.flags and 1 != 0
            val vend = bs - (if (root) 40 else 0)
            val n = ents.size
            // fixed-kv trees (object maps): macOS sizes the table for the node's maximum capacity
            val tlen = if (fixed) (if (info.level == 0) ((bs - 56) / 36) * 4 else ((bs - 56) / 28) * 4) else maxOf(info.tlen, 8 * n)
            if (fixed && n * 4 > tlen) return false
            val kstart = 56 + info.toff + tlen
            val ksz = ents.sumOf { it.key.size }; val vsz = ents.sumOf { it.value.size }
            if (kstart + ksz + vsz > vend) return false
            java.util.Arrays.fill(b, 56, vend, 0)
            var kpos = 0; var vpos = 0
            for ((i, e) in ents.withIndex()) {
                System.arraycopy(e.key, 0, b, kstart + kpos, e.key.size)
                vpos += e.value.size
                if (e.value.isNotEmpty()) System.arraycopy(e.value, 0, b, vend - vpos, e.value.size)
                if (fixed) { w16(b, 56 + info.toff + 4 * i, kpos); w16(b, 58 + info.toff + 4 * i, vpos) }
                else {
                    val t = 56 + info.toff + 8 * i
                    w16(b, t, kpos); w16(b, t + 2, e.key.size); w16(b, t + 4, if (e.value.isEmpty()) 0xFFFF else vpos); w16(b, t + 6, e.value.size)
                }
                kpos += e.key.size
            }
            w16(b, 32, info.flags); w16(b, 34, info.level); w32(b, 36, n.toLong()); w16(b, 40, info.toff); w16(b, 42, tlen)
            w16(b, 44, kpos); w16(b, 46, (vend - vpos) - (kstart + kpos))
            w16(b, 48, 0xFFFF); w16(b, 50, 0); w16(b, 52, 0xFFFF); w16(b, 54, 0)
            info.tlen = tlen
            return true
        }

        // ---- B-tree engine ----
        inner class BTree(val rootPaddr: Long, val fixed: Boolean, val virtualChildren: Boolean, val cmp: (ByteArray, ByteArray) -> Int) {
            fun child(ptr: ByteArray): Long {
                val p = r64(ptr, 0)
                return if (virtualChildren) (omap.omapLookup(p, volXid) ?: throw Fail("unmapped node $p")) else p
            }

            fun omapLookup(oid: Long, xid: Long): Long? {
                var paddr = rootPaddr
                while (true) {
                    val (info, ents) = decode(blk(paddr), true)
                    if (info.level == 0) {
                        var best: Entry? = null; var bestXid = -1L
                        for (e in ents) if (r64(e.key, 0) == oid && java.lang.Long.compareUnsigned(r64(e.key, 8), xid) <= 0 && r64(e.key, 8) >= bestXid) { best = e; bestXid = r64(e.key, 8) }
                        return best?.let { r64(it.value, 8) }
                    }
                    var pick: Entry? = null
                    for (e in ents) { val ok = r64(e.key, 0); if (java.lang.Long.compareUnsigned(ok, oid) <= 0) pick = e else break }
                    paddr = r64((pick ?: return null).value, 0)
                }
            }

            fun locate(key: ByteArray): Loc {
                val path = ArrayList<Step>(); var paddr = rootPaddr; var guard = 0
                while (guard++ < 32) {
                    val (info, ents) = decode(blk(paddr), fixed)
                    if (info.level == 0) {
                        var pos = ents.size; var found = false
                        for (i in ents.indices) {
                            val c = cmp(ents[i].key, key)
                            if (c == 0) { pos = i; found = true; break }
                            if (c > 0) { pos = i; break }
                        }
                        return Loc(path, paddr, pos, found)
                    }
                    var pick = 0
                    for (i in ents.indices) if (cmp(ents[i].key, key) <= 0) pick = i else break
                    path.add(Step(paddr, pick)); paddr = child(ents[pick].value)
                }
                throw Fail("tree too deep")
            }

            fun lookup(key: ByteArray): ByteArray? {
                val loc = locate(key)
                return if (loc.found) decode(blk(loc.leaf), fixed).second[loc.pos].value else null
            }

            /** All entries whose key lies in [lo, hi] (inclusive), in order. */
            fun range(lo: ByteArray, hi: ByteArray): List<Entry> {
                val out = ArrayList<Entry>()
                fun dfs(paddr: Long) {
                    val (info, ents) = decode(blk(paddr), fixed)
                    if (info.level == 0) { for (e in ents) if (cmp(e.key, lo) >= 0 && cmp(e.key, hi) <= 0) out.add(e); return }
                    for (i in ents.indices) {
                        if (cmp(ents[i].key, hi) > 0) break
                        if (i + 1 < ents.size && cmp(ents[i + 1].key, lo) <= 0) continue
                        dfs(child(ents[i].value))
                    }
                }
                dfs(rootPaddr); return out
            }

            private fun rootInfo(): ByteArray = blk(rootPaddr)
            fun adjust(dKeys: Long, dNodes: Long, keyLen: Int, valLen: Int) {
                val r = rootInfo(); val o = bs - 24
                w64(r, o + 8, r64(r, o + 8) + dKeys); w64(r, o + 16, r64(r, o + 16) + dNodes)
                if (keyLen > r32(r, o)) w32(r, o, keyLen.toLong()); if (valLen > r32(r, o + 4)) w32(r, o + 4, valLen.toLong())
                touch(rootPaddr)
            }

            fun insert(key: ByteArray, value: ByteArray) {
                val loc = locate(key)
                if (loc.found) throw Fail("duplicate key")
                insertAt(loc.leaf, loc.pos, Entry(key, value), loc.path)
                if (!fixed) adjust(1, 0, key.size, value.size)
            }

            private fun newNode(template: ByteArray, level: Int, leafFlag: Boolean): Pair<Long, ByteArray> {
                val paddr = allocate(1, requireOne = true).first().start
                val nb = ByteArray(bs)
                System.arraycopy(template, 8, nb, 8, 24)             // oid/xid/type/subtype copied, fixed up below
                w64(nb, 16, volXid)
                w32(nb, 24, (r32(template, 24) and 0xFFFF0000L) or 3L)   // non-root nodes are OBJECT_TYPE_BTREE_NODE
                val oid: Long
                if (virtualChildren) { oid = nextOid++; w64(nb, 8, oid); omap.insertOmap(oid, volXid, paddr) } else { oid = paddr; w64(nb, 8, paddr) }
                val info = Info((if (leafFlag) 2 else 0) or (if (fixed) 4 else 0), level, 0, 0)
                cache[paddr] = nb; touch(paddr)
                w16(nb, 32, info.flags)
                return oid to nb
            }

            private fun insertAt(paddr: Long, pos: Int, e: Entry, path: List<Step>) {
                val nb = blk(paddr); val (info, ents) = decode(nb, fixed)
                ents.add(pos, e)
                if (encode(nb, info, ents, fixed)) {
                    touch(paddr)
                    if (pos == 0 && path.isNotEmpty() && info.level >= 0) fixParentKey(path, ents[0].key, paddr)
                    return
                }
                // split
                val half = ents.sumOf { it.key.size + it.value.size + 8 } / 2
                var acc = 0; var k = 0
                while (k < ents.size - 1 && acc + ents[k].key.size + ents[k].value.size + 8 <= half) { acc += ents[k].key.size + ents[k].value.size + 8; k++ }
                if (k == 0) k = 1
                val left = ArrayList(ents.subList(0, k)); val right = ArrayList(ents.subList(k, ents.size))
                val leaf = info.level == 0
                if (path.isEmpty()) {            // root: keep it in place, move both halves to new children
                    val (oidL, nl) = newNode(nb, info.level, leaf); val (oidR, nr) = newNode(nb, info.level, leaf)
                    val il = Info(nl.let { r16(it, 32) }, info.level, 0, 0); val ir = Info(r16(nr, 32), info.level, 0, 0)
                    if (!encode(nl, il, left, fixed) || !encode(nr, ir, right, fixed)) throw Fail("record too large to split")
                    val ptr: (Long) -> ByteArray = { le64(it) }
                    val rootEnts = arrayListOf(Entry(left[0].key, ptr(oidL)), Entry(right[0].key, ptr(oidR)))
                    val newInfo = Info(info.flags and 2.inv(), info.level + 1, info.toff, info.tlen)
                    if (!encode(nb, newInfo, rootEnts, fixed)) throw Fail("root index overflow")
                    touch(paddr); touch(cacheKeyOf(nl)); touch(cacheKeyOf(nr))
                    adjustNodes(2)
                } else {
                    val (oidR, nr) = newNode(nb, info.level, leaf)
                    val ir = Info(r16(nr, 32), info.level, 0, 0)
                    if (!encode(nb, info, left, fixed) || !encode(nr, ir, right, fixed)) throw Fail("record too large to split")
                    touch(paddr); touch(cacheKeyOf(nr)); adjustNodes(1)
                    val parent = path.last()
                    insertAt(parent.paddr, parent.idx + 1, Entry(right[0].key, le64(oidR)), path.subList(0, path.size - 1))
                }
            }

            private fun cacheKeyOf(b: ByteArray): Long = cache.entries.first { it.value === b }.key
            private fun adjustNodes(d: Long) { val r = rootInfo(); val o = bs - 24; w64(r, o + 16, r64(r, o + 16) + d); touch(rootPaddr) }

            private fun fixParentKey(path: List<Step>, newKey: ByteArray, child: Long) {
                val p = path.last(); val pb = blk(p.paddr); val (pi, pe) = decode(pb, fixed)
                if (cmp(pe[p.idx].key, newKey) == 0) return
                pe[p.idx] = Entry(newKey, pe[p.idx].value)
                if (encode(pb, pi, pe, fixed)) { touch(p.paddr); if (p.idx == 0 && path.size > 1) fixParentKey(path.subList(0, path.size - 1), newKey, p.paddr) }
            }

            fun delete(key: ByteArray): ByteArray {
                val loc = locate(key)
                if (!loc.found) throw Fail("record not found")
                val nb = blk(loc.leaf); val (info, ents) = decode(nb, fixed)
                val removed = ents.removeAt(loc.pos)
                if (!fixed) adjust(-1, 0, 0, 0)
                if (ents.isEmpty() && loc.path.isNotEmpty()) removeNode(loc.leaf, loc.path)
                else {
                    encode(nb, info, ents, fixed); touch(loc.leaf)
                    if (loc.pos == 0 && ents.isNotEmpty() && loc.path.isNotEmpty()) fixParentKey(loc.path, ents[0].key, loc.leaf)
                }
                return removed.value
            }

            private fun removeNode(paddr: Long, path: List<Step>) {
                val p = path.last(); val pb = blk(p.paddr); val (pi, pe) = decode(pb, fixed)
                val removedPtr = pe.removeAt(p.idx)
                if (virtualChildren) omap.deleteOmap(r64(removedPtr.value, 0), volXid)
                freeBlock(paddr); cache.remove(paddr); dirty.remove(paddr)
                adjustNodes(-1)
                if (pe.isEmpty() && path.size > 1) removeNode(p.paddr, path.subList(0, path.size - 1))
                else { encode(pb, pi, pe, fixed); touch(p.paddr) }
            }

            // ---- object-map specific (fixed-kv, physical children) ----
            fun insertOmap(oid: Long, xid: Long, paddr: Long) {
                val key = ByteArray(16).also { w64(it, 0, oid); w64(it, 8, xid) }
                val v = ByteArray(16).also { w32(it, 0, 0); w32(it, 4, bs.toLong()); w64(it, 8, paddr) }
                val loc = locate(key)
                if (loc.found) throw Fail("omap entry exists")
                insertAt(loc.leaf, loc.pos, Entry(key, v), loc.path)
                adjust(1, 0, 16, 16)
            }
            fun deleteOmap(oid: Long, xid: Long) {
                val key = ByteArray(16).also { w64(it, 0, oid); w64(it, 8, xid) }
                val loc = locate(key); if (!loc.found) return
                val nb = blk(loc.leaf); val (info, ents) = decode(nb, true)
                ents.removeAt(loc.pos); encode(nb, info, ents, true); touch(loc.leaf)
                val r = blk(rootPaddr); val o = bs - 24; w64(r, o + 8, r64(r, o + 8) - 1); touch(rootPaddr)
            }
        }

        // ---- space manager ----
        private fun smBlock() = blk(smPaddr)
        private val chunkCount: Long get() = r64(smBlock(), 56)
        private val blocksPerChunk: Long get() = r32(smBlock(), 36)
        private val chunksPerCib: Long get() = r32(smBlock(), 40)
        private fun cibPaddr(i: Long): Long { val sm = smBlock(); return r64(sm, r32(sm, 80).toInt() + 8 * i.toInt()) }
        private fun chunkInfoOff(chunk: Long) = 40 + 32 * (chunk % chunksPerCib).toInt()
        private var blocksAllocated = 0L
        private var blocksFreed = 0L

        fun allocate(want: Long, requireOne: Boolean = false): List<Run> {
            val sm = smBlock()
            if (r64(sm, 72) < want) throw Fail("not enough free space")
            val runs = ArrayList<Run>(); var remaining = want
            val chunks = chunkCount
            var c = 0L
            while (c < chunks && remaining > 0) {
                val cib = blk(cibPaddr(c / chunksPerCib)); val o = chunkInfoOff(c)
                var bitmapAddr = r64(cib, o + 24)
                val total = r32(cib, o + 16).toInt(); val free = r32(cib, o + 20)
                // A chunk that was never used has no bitmap yet: claim a block of the internal pool for it.
                if (bitmapAddr == 0L && free > 0) bitmapAddr = createChunkBitmap(cibPaddr(c / chunksPerCib), cib, o)
                if (bitmapAddr != 0L && free > 0) {
                    val bm = blk(bitmapAddr); rawBlocks.add(bitmapAddr)
                    var i = 0
                    while (i < total && remaining > 0) {
                        if ((bm[i / 8].toInt() shr (i % 8)) and 1 == 0) {
                            var j = i
                            while (j < total && remaining - (j - i) > 0 && ((bm[j / 8].toInt() shr (j % 8)) and 1) == 0) j++
                            val n = (j - i).toLong()
                            for (b in i until j) bm[b / 8] = (bm[b / 8].toInt() or (1 shl (b % 8))).toByte()
                            val start = r64(cib, o + 8) + i
                            runs.add(Run(start, n)); remaining -= n
                            w32(cib, o + 20, r32(cib, o + 20) - n); touch(cibPaddr(c / chunksPerCib)); touch(bitmapAddr)
                            i = j
                            if (requireOne && remaining > 0) throw Fail("fragmented")
                        } else i++
                    }
                }
                c++
            }
            if (remaining > 0) throw Fail("not enough free space in initialised chunks")
            w64(sm, 72, r64(sm, 72) - want); touch(smPaddr)
            blocksAllocated += want
            return runs
        }

        /**
         * Creates the (all-free) allocation bitmap of a chunk that has none, in a free block of the space
         * manager's internal pool; the pool's own bitmap (current ring slot) is updated in place.
         */
        private fun createChunkBitmap(cibPaddr: Long, cib: ByteArray, infoOff: Int): Long {
            val sm = smBlock()
            val ipBlocks = r64(sm, 152); val bmSize = r32(sm, 160).toInt(); val ipBmBase = r64(sm, 168); val ipBase = r64(sm, 176)
            val slot = r32(sm, r32(sm, 328).toInt()).toInt()
            val bmBlocks = (0 until bmSize).map { ipBmBase + slot.toLong() * bmSize + it }
            val maps = bmBlocks.map { blk(it) }
            var found = -1L
            for (i in 0 until ipBlocks) {
                val m = maps[(i / 8 / bs).toInt()]; val bi = ((i / 8) % bs).toInt()
                if ((m[bi].toInt() shr (i % 8).toInt()) and 1 == 0) { found = i; break }
            }
            if (found < 0) throw Fail("no free block in the internal pool for a new chunk bitmap")
            val m = maps[(found / 8 / bs).toInt()]; val bi = ((found / 8) % bs).toInt()
            m[bi] = (m[bi].toInt() or (1 shl (found % 8).toInt())).toByte()
            rawBlocks.add(bmBlocks[(found / 8 / bs).toInt()]); touch(bmBlocks[(found / 8 / bs).toInt()])
            val addr = ipBase + found
            val bm = blk(addr); java.util.Arrays.fill(bm, 0.toByte()); rawBlocks.add(addr); touch(addr)
            w64(cib, infoOff + 24, addr); w64(cib, infoOff, nxXid); touch(cibPaddr)
            return addr
        }

        fun freeBlock(paddr: Long) = freeRun(paddr, 1)
        fun freeRun(start: Long, count: Long) {
            val sm = smBlock()
            var b = start; var left = count
            while (left > 0) {
                val chunk = b / blocksPerChunk
                val cibIdx = chunk / chunksPerCib; val cibP = cibPaddr(cibIdx); val cib = blk(cibP); val o = chunkInfoOff(chunk)
                val bitmapAddr = r64(cib, o + 24); if (bitmapAddr == 0L) throw Fail("free in a chunk without bitmap")
                val bm = blk(bitmapAddr); rawBlocks.add(bitmapAddr)
                val chunkStart = r64(cib, o + 8); val inChunk = minOf(left, chunkStart + r32(cib, o + 16) - b)
                for (x in b until b + inChunk) { val i = (x - chunkStart).toInt(); bm[i / 8] = (bm[i / 8].toInt() and (1 shl (i % 8)).inv()).toByte() }
                w32(cib, o + 20, r32(cib, o + 20) + inChunk); touch(cibP); touch(bitmapAddr)
                b += inChunk; left -= inChunk
            }
            w64(sm, 72, r64(sm, 72) + count); touch(smPaddr)
            blocksFreed += count
        }

        // ---- names ----
        fun nameField(name: String): Int {
            var n = Normalizer.normalize(name, Normalizer.Form.NFD)
            if (caseInsensitive) n = Normalizer.normalize(n.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val bytes = ByteArray(n.codePointCount(0, n.length) * 4); var i = 0
            n.codePoints().forEach { cp -> w32(bytes, i, cp.toLong()); i += 4 }
            val h = crc32c(bytes) and 0x3FFFFF
            return ((h shl 10) or (name.toByteArray(Charsets.UTF_8).size + 1).toLong()).toInt()
        }
        fun drecKey(parent: Long, name: String): ByteArray {
            val nb = name.toByteArray(Charsets.UTF_8)
            val k = ByteArray(8 + 4 + nb.size + 1)
            w64(k, 0, (T_DREC shl 60) or parent); w32(k, 8, nameField(name).toLong() and 0xFFFFFFFFL); System.arraycopy(nb, 0, k, 12, nb.size)
            return k
        }
        private fun validName(n: String) = n.isNotEmpty() && n.toByteArray(Charsets.UTF_8).size <= 254 && n.none { it == '/' || it.code == 0 }

        // ---- records ----
        private fun xfields(name: String, dstreamSize: Long, dstreamAlloc: Long): ByteArray {
            val nb = name.toByteArray(Charsets.UTF_8) + 0
            val nd = nb.copyOf((nb.size + 7) / 8 * 8)
            val hasDs = dstreamSize >= 0
            val data = if (hasDs) nd + ByteArray(40).also { w64(it, 0, dstreamSize); w64(it, 8, dstreamAlloc); w64(it, 24, dstreamSize) } else nd
            val out = ByteArray(4 + 4 * (if (hasDs) 2 else 1) + data.size)
            w16(out, 0, if (hasDs) 2 else 1); w16(out, 2, data.size)
            var o = 4
            out[o] = 4; out[o + 1] = 2; w16(out, o + 2, nb.size); o += 4
            if (hasDs) { out[o] = 8; out[o + 1] = 0x20; w16(out, o + 2, 40); o += 4 }
            System.arraycopy(data, 0, out, o, data.size)
            return out
        }

        private fun inodeVal(parent: Long, ino: Long, mode: Int, name: String, dsSize: Long, dsAlloc: Long): ByteArray {
            val head = ByteArray(92)
            w64(head, 0, parent); w64(head, 8, ino)
            for (o in intArrayOf(16, 24, 32, 40)) w64(head, o, now)
            w64(head, 48, 0x8000); w32(head, 56, 1.toLong().let { if (mode and 0xF000 == 0x4000) 0 else 1 }); w32(head, 64, 1)
            w32(head, 72, 99); w32(head, 76, 99); w16(head, 80, mode)
            return head + xfields(name, dsSize, dsAlloc)
        }

        private fun counter(off: Int, d: Long) { w64(vsb, off, r64(vsb, off) + d); touch(vsbPaddr) }
        private fun bumpParent(parent: Long, d: Int) {
            val key = hdrKey(parent, T_INODE)
            val loc = fs.locate(key); if (!loc.found) throw Fail("parent directory $parent not found")
            val nb = blk(loc.leaf); val (info, ents) = decode(nb, false)
            val v = ents[loc.pos].value
            if (r16(v, 80) and 0xF000 != 0x4000) throw Fail("parent is not a directory")
            w32(v, 56, (r32(v, 56) + d) and 0xFFFFFFFFL); w64(v, 24, now); w64(v, 32, now)
            if (!encode(nb, info, ents, false)) throw Fail("inode update failed"); touch(loc.leaf)
        }

        fun createEntry(parent: Long, name: String, isDir: Boolean, size: Long, input: InputStream?, onProgress: ((Long) -> Unit)?) {
            if (!validName(name)) throw Fail("invalid name")
            val dk = drecKey(parent, name)
            if (fs.lookup(dk) != null) throw Fail("'$name' already exists")
            val ino = r64(vsb, 176); w64(vsb, 176, ino + 1)
            val nblocks = (size + bs - 1) / bs
            val runs = if (!isDir && size > 0) allocate(nblocks) else emptyList()
            // data
            if (runs.isNotEmpty() && input != null) {
                val buf = ByteArray(1 shl 20); var written = 0L
                for (r in runs) {
                    var off = 0L; val runBytes = r.count * bs
                    while (off < runBytes) {
                        val want = minOf(buf.size.toLong(), runBytes - off).toInt()
                        val real = minOf(want.toLong(), size - written).toInt()
                        var got = 0
                        while (got < real) { val n = input.read(buf, got, real - got); if (n < 0) break; got += n }
                        if (got < real) throw Fail("source ended early")
                        java.util.Arrays.fill(buf, got, want, 0)
                        if (!dev.writeSectors(lba(r.start) + off / 512, buf.copyOf(want))) throw Fail("write error")
                        off += want; written += got; onProgress?.invoke(written)
                    }
                }
            }
            fs.insert(hdrKey(ino, T_INODE), inodeVal(parent, ino, if (isDir) 0x41ED else 0x81A4, name, if (!isDir && size > 0) size else -1, nblocks * bs))
            fs.insert(dk, ByteArray(18).also { w64(it, 0, ino); w64(it, 8, now); w16(it, 16, if (isDir) 4 else 8) })
            if (!isDir && size > 0) {
                fs.insert(hdrKey(ino, T_DSTREAM_ID), le32(1))
                var logical = 0L
                for (r in runs) {
                    val ek = hdrKey(ino, T_EXTENT_FILE) + le64(logical)
                    fs.insert(ek, ByteArray(24).also { w64(it, 0, r.count * bs); w64(it, 8, r.start); w64(it, 16, 0) })
                    ext.insert(hdrKey(r.start, T_PHYS_EXTENT), ByteArray(20).also { w64(it, 0, (1L shl 60) or r.count); w64(it, 8, ino); w32(it, 16, 1) })
                    logical += r.count * bs
                }
            }
            bumpParent(parent, +1)
            counter(if (isDir) 192 else 184, +1)
        }

        // ---- delete / rename ----
        private fun findDrec(parent: Long, name: String, ino: Long): Entry? {
            val k = drecKey(parent, name)
            fs.lookup(k)?.let { return Entry(k, it) }
            // fallback (unicode edge cases): scan the directory
            return fs.range(hdrKey(parent, T_DREC), hdrKey(parent, T_DREC + 1)).firstOrNull { e ->
                val n = String(e.key, 12, e.key.size - 13, Charsets.UTF_8)
                (ino != 0L && r64(e.value, 0) == ino) || (ino == 0L && n == name)
            }
        }

        fun deleteEntry(parent: Long, name: String, expectIno: Long) {
            val d = findDrec(parent, name, expectIno) ?: throw Fail("'$name' not found")
            val ino = r64(d.value, 0)
            if (expectIno != 0L && ino != expectIno) throw Fail("directory entry changed")
            val isDir = r16(d.value, 16) and 0xF == 4
            removeInode(ino, isDir)
            fs.delete(d.key)
            bumpParent(parent, -1)
        }

        private fun removeInode(ino: Long, isDir: Boolean) {
            if (isDir) for (c in fs.range(hdrKey(ino, T_DREC), hdrKey(ino, T_DREC + 1))) {
                val cname = String(c.key, 12, c.key.size - 13, Charsets.UTF_8)
                removeInode(r64(c.value, 0), r16(c.value, 16) and 0xF == 4)
                fs.delete(c.key)
            }
            val recs = fs.range(hdrKey(ino, 0), hdrKey(ino, 15))
            val inode = recs.firstOrNull { typeOf(it.key) == T_INODE } ?: throw Fail("inode $ino missing")
            if (!isDir && r32(inode.value, 56) > 1) throw Fail("hard-linked file")
            for (r in recs) when (typeOf(r.key)) {
                T_XATTR -> { if (r16(r.value, 0) and 1 != 0) throw Fail("extended attribute stored in separate blocks - delete it on a Mac") }
                T_DSTREAM_ID -> if (r32(r.value, 0) > 1) throw Fail("shared data stream")
                T_EXTENT_FILE -> {
                    val len = r64(r.value, 0) and ((1L shl 56) - 1); val phys = r64(r.value, 8)
                    if (phys != 0L) {
                        val ek = hdrKey(phys, T_PHYS_EXTENT); val ev = ext.lookup(ek) ?: throw Fail("extent reference missing")
                        if (r32(ev, 16) > 1) throw Fail("shared extent")
                        ext.delete(ek); freeRun(phys, len / bs)
                    }
                }
                T_INODE, 10L -> {}
                else -> throw Fail("unsupported record type ${typeOf(r.key)} on inode $ino")
            }
            for (r in recs) fs.delete(r.key)
            counter(if (isDir) 192 else 184, -1)
        }

        fun rename(parent: Long, oldName: String, expectIno: Long, newName: String) {
            if (!validName(newName)) throw Fail("invalid name")
            val d = findDrec(parent, oldName, expectIno) ?: throw Fail("'$oldName' not found")
            val ino = r64(d.value, 0)
            val newKey = drecKey(parent, newName)
            if (!oldName.equals(newName, ignoreCase = true) && fs.lookup(newKey) != null) throw Fail("'$newName' already exists")
            fs.delete(d.key)
            if (fs.lookup(newKey) != null) throw Fail("'$newName' already exists")
            fs.insert(newKey, d.value.copyOf())
            // inode: replace the name xfield, bump times
            val ik = hdrKey(ino, T_INODE)
            val iv = fs.delete(ik)
            val head = iv.copyOfRange(0, 92)
            w64(head, 24, now); w64(head, 32, now)
            val xf = iv.copyOfRange(92, iv.size)
            fs.insert(ik, head + rebuildXf(xf, newName))
            bumpParent(parent, 0)
        }

        private fun rebuildXf(xf: ByteArray, newName: String): ByteArray {
            val num = if (xf.size >= 4) r16(xf, 0) else 0
            val fields = ArrayList<Triple<Int, Int, ByteArray>>()      // type, flags, data (unpadded)
            var dataPos = 4 + 4 * num
            for (i in 0 until num) {
                val t = xf[4 + 4 * i].toInt() and 0xFF; val fl = xf[5 + 4 * i].toInt() and 0xFF; val sz = r16(xf, 6 + 4 * i)
                fields.add(Triple(t, fl, xf.copyOfRange(dataPos, dataPos + sz)))
                dataPos += (sz + 7) / 8 * 8
            }
            val nb = newName.toByteArray(Charsets.UTF_8) + 0
            var replaced = false
            val out = fields.map { if (it.first == 4) { replaced = true; Triple(4, it.second, nb) } else it }.toMutableList()
            if (!replaced) out.add(0, Triple(4, 2, nb))
            val dataLen = out.sumOf { (it.third.size + 7) / 8 * 8 }
            val res = ByteArray(4 + 4 * out.size + dataLen)
            w16(res, 0, out.size); w16(res, 2, dataLen)
            var hp = 4; var dp = 4 + 4 * out.size
            for ((t, fl, data) in out) {
                res[hp] = t.toByte(); res[hp + 1] = fl.toByte(); w16(res, hp + 2, data.size); hp += 4
                System.arraycopy(data, 0, res, dp, data.size); dp += (data.size + 7) / 8 * 8
            }
            return res
        }

        // ---- commit ----
        fun commit() {
            if (blocksAllocated != 0L || blocksFreed != 0L) {
                counter(88, blocksAllocated - blocksFreed)                 // apfs_fs_alloc_count
                counter(224, blocksAllocated); counter(232, blocksFreed)   // total blocks alloced / freed
            }
            w64(vsb, 256, now); touch(vsbPaddr)
            if (nextOid != r64(nx, 88)) {
                w64(nx, 88, nextOid); touch(nxPaddr)
                // keep block 0 (the unmount-time copy) in step when it mirrors the same checkpoint
                val b0 = blk(0)
                if (nxPaddr != 0L && r64(b0, 16) == nxXid) { w64(b0, 88, nextOid); touch(0) }
                // and any other descriptor-area copy of the same transaction
                val descBase = r64(blk(0), 112); val descBlocks = r32(blk(0), 104).toInt()
                for (i in 0 until descBlocks) {
                    val c = blk(descBase + i)
                    if (descBase + i != nxPaddr && r32(c, 32) == 0x4253584EL && r64(c, 16) == nxXid) { w64(c, 88, nextOid); touch(descBase + i) }
                }
            }
            // metadata order: bitmaps/space manager, extent-ref tree, fs tree + object maps, superblocks
            val ordered = dirty.sortedBy { p -> when { p in rawBlocks -> 0; p == smPaddr -> 1; p == vsbPaddr || p == nxPaddr || p == 0L -> 9; else -> 5 } }
            for (p in ordered) {
                val b = cache[p] ?: continue
                if (p !in rawBlocks) System.arraycopy(ByteArray(8).also { w64(it, 0, fletcher(b)) }, 0, b, 0, 8)
                if (!dev.writeSectors(lba(p), b)) throw Fail("write error at block $p")
            }
            dev.flushCache()
        }
    }
}
