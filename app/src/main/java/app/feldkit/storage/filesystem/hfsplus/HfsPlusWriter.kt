package app.feldkit.storage.filesystem.hfsplus

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemWriter
import java.io.InputStream
import java.text.Normalizer

/**
 * HFS+ writer (create / write / rename / delete) working directly on the catalog B-tree.
 *
 * Every operation runs in a [Tx]: all metadata (catalog nodes, allocation bitmap, volume header) is
 * modified in memory and written back only when the operation has fully succeeded, in the order
 * catalog nodes -> allocation bitmap -> volume header (+ alternate header). A failed operation
 * therefore leaves the volume untouched (apart from unreferenced data blocks).
 *
 * Safety rules: refuses to write when the volume was not cleanly unmounted, is software-locked, or has
 * a journal with pending transactions; refuses to delete items that carry extended attributes or
 * fork extents stored in the extents-overflow file (those need B-trees this writer does not edit).
 * Limitations: the catalog file must have free nodes (it is not grown) and its extents must be inline.
 */
class HfsPlusWriter(
    private val dev: BlockDeviceReader,
    private val partitionStartLba: Long,
    @Suppress("unused") private val initialHeader: HfsPlusVolumeHeader
) : FileSystemWriter {

    companion object {
        private const val TAG = "FeldKit"
        private const val HFS_EPOCH_DELTA = 2082844800L
        private const val ATTR_UNMOUNTED = 0x00000100
        private const val ATTR_INCONSISTENT = 0x00004000
        private const val ATTR_SOFTLOCK = 0x00008000
        private const val ATTR_JOURNALED = 0x00002000
        private const val KIND_LEAF = -1
        private const val KIND_INDEX = 0
        private const val KIND_HEADER = 1
    }

    private class Fail(msg: String) : Exception(msg)
    private class Ext(val start: Long, val count: Long)
    private class Step(val node: Int, val idx: Int)
    private class Loc(val path: List<Step>, val leaf: Int, val pos: Int, val found: Boolean)
    private class Fork(val logical: Long, val total: Long, val ext: List<Ext>)

    // ── byte helpers (big-endian) ─────────────────────────────────────────────
    private fun u16(a: ByteArray, o: Int) = ((a[o].toInt() and 0xFF) shl 8) or (a[o + 1].toInt() and 0xFF)
    private fun u32(a: ByteArray, o: Int): Long = ((a[o].toLong() and 0xFF) shl 24) or ((a[o + 1].toLong() and 0xFF) shl 16) or ((a[o + 2].toLong() and 0xFF) shl 8) or (a[o + 3].toLong() and 0xFF)
    private fun u64(a: ByteArray, o: Int): Long = (u32(a, o) shl 32) or u32(a, o + 4)
    private fun p16(a: ByteArray, o: Int, v: Int) { a[o] = (v shr 8).toByte(); a[o + 1] = v.toByte() }
    private fun p32(a: ByteArray, o: Int, v: Long) { for (k in 0..3) a[o + k] = (v shr (8 * (3 - k))).toByte() }
    private fun p64(a: ByteArray, o: Int, v: Long) { p32(a, o, v ushr 32); p32(a, o + 4, v and 0xFFFFFFFFL) }

    private fun parseFork(a: ByteArray, o: Int): Fork {
        val exts = (0 until 8).map { Ext(u32(a, o + 16 + it * 8), u32(a, o + 20 + it * 8)) }.filter { it.count > 0 }
        return Fork(u64(a, o), u32(a, o + 12), exts)
    }

    private fun nowHfs(): Long = System.currentTimeMillis() / 1000 + HFS_EPOCH_DELTA

    // ── public API ────────────────────────────────────────────────────────────

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean =
        writeFileStream(parentEntry, name, data.size.toLong(), java.io.ByteArrayInputStream(data), null)

    override fun writeFileStream(
        parentEntry: FileSystemEntry, name: String, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?
    ): Boolean = run("writeFile $name") { tx ->
        val ext = tx.allocate(size)
        tx.streamToExtents(ext, size, input, onProgress)
        tx.createEntry(parentEntry.hfsCatalogId.toLong(), name, false, size, ext)
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean =
        run("createDirectory $name") { tx -> tx.createEntry(parentEntry.hfsCatalogId.toLong(), name, true, 0, emptyList()) }

    override fun deleteEntry(entry: FileSystemEntry): Boolean =
        run("deleteEntry ${entry.name}") { tx -> tx.deleteByKey(entry.hfsParentId.toLong(), entry.name, entry.hfsCatalogId.toLong()) }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean =
        run("renameEntry ${entry.name}") { tx -> tx.rename(entry.hfsParentId.toLong(), entry.name, entry.hfsCatalogId.toLong(), newName) }

    private fun run(what: String, block: (Tx) -> Unit): Boolean = try {
        val tx = Tx()
        block(tx)
        tx.commit()
        Log.i(TAG, "HFS+ $what OK")
        true
    } catch (e: Fail) {
        Log.w(TAG, "HFS+ $what refused: ${e.message}"); false
    } catch (e: Exception) {
        Log.e(TAG, "HFS+ $what failed", e); false
    }

    // ── transaction ───────────────────────────────────────────────────────────

    private inner class Tx {
        val vh: ByteArray
        val bs: Int
        val totalBlocks: Long
        val catFork: Fork
        val allocFork: Fork
        val attrFork: Fork
        var caseSensitive: Boolean = false
        lateinit var cat: Tree
        private val bitPages = HashMap<Int, ByteArray>()
        private val dirtyPages = sortedSetOf<Int>()
        private var freeBlocks: Long
        private var nextAlloc: Long
        private var nextCnid: Long
        private var fileCount: Long
        private var folderCount: Long

        init {
            val sector = dev.readSectors(partitionStartLba + 2, 1) ?: throw Fail("cannot read volume header")
            vh = sector
            if (u16(vh, 0) != 0x482B && u16(vh, 0) != 0x4858) throw Fail("not an HFS+ volume")
            val attrs = u32(vh, 4).toInt()
            if (attrs and ATTR_UNMOUNTED == 0) throw Fail("volume was not cleanly unmounted - run Disk Utility First Aid on the Mac")
            if (attrs and (ATTR_INCONSISTENT or ATTR_SOFTLOCK) != 0) throw Fail("volume is locked or flagged inconsistent")
            bs = u32(vh, 40).toInt()
            totalBlocks = u32(vh, 44)
            freeBlocks = u32(vh, 48); nextAlloc = u32(vh, 52); nextCnid = u32(vh, 64)
            fileCount = u32(vh, 32); folderCount = u32(vh, 36)
            allocFork = parseFork(vh, 112); catFork = parseFork(vh, 272); attrFork = parseFork(vh, 352)
            if (attrs and ATTR_JOURNALED != 0) checkJournalClean(u32(vh, 12))
            cat = Tree(catFork) { a, b -> compare(a, b) }
            val h0 = cat.node(0)
            caseSensitive = (h0[14 + 37].toInt() and 0xFF) == 0xBC
            if (catFork.total * bs < u32(h0, 14 + 22) * cat.nodeSize) throw Fail("catalog extents are not inline")
        }

        private fun checkJournalClean(jib: Long) {
            val jibBytes = dev.readSectors(partitionStartLba + jib * (bs / 512), 1) ?: throw Fail("cannot read journal info")
            val offset = u64(jibBytes, 36)   // JournalInfoBlock.offset
            val jh = dev.readSectors(partitionStartLba + offset / 512, 1) ?: throw Fail("cannot read journal header")
            val bb = java.nio.ByteBuffer.wrap(jh)
            // journal_header: magic @0, endian @4, start @8, end @16 (written in the creating machine's byte order)
            val order = when {
                bb.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) == 0x4a4e4c78 -> java.nio.ByteOrder.LITTLE_ENDIAN
                bb.order(java.nio.ByteOrder.BIG_ENDIAN).getInt(0) == 0x4a4e4c78 -> java.nio.ByteOrder.BIG_ENDIAN
                else -> throw Fail("journal header unreadable")
            }
            bb.order(order)
            val start = bb.getLong(8)
            val end = bb.getLong(16)
            if (start != end) throw Fail("journal has pending transactions - mount the volume on a Mac first")
        }

        // ── fork I/O (inline extents) ────────────────────────────────────────
        fun forkRun(f: Fork, byteOff: Long): Pair<Long, Long>? {   // (lba, sectors available contiguously)
            var fb = byteOff / bs; var acc = 0L
            for (e in f.ext) {
                if (fb < acc + e.count) {
                    val inBlock = byteOff % bs
                    val lba = partitionStartLba + (e.start + (fb - acc)) * (bs / 512) + inBlock / 512
                    val sectors = (e.count - (fb - acc)) * (bs / 512) - inBlock / 512
                    return lba to sectors
                }
                acc += e.count
            }
            return null
        }

        fun readFork(f: Fork, off: Long, len: Int): ByteArray {
            val out = ByteArray(len); var done = 0
            while (done < len) {
                val (lba, avail) = forkRun(f, off + done) ?: throw Fail("fork read beyond extents")
                val n = minOf(avail, ((len - done) / 512).toLong()).toInt()
                val chunk = dev.readSectors(lba, n) ?: throw Fail("read error")
                System.arraycopy(chunk, 0, out, done, n * 512); done += n * 512
            }
            return out
        }

        fun writeFork(f: Fork, off: Long, data: ByteArray) {
            var done = 0
            while (done < data.size) {
                val (lba, avail) = forkRun(f, off + done) ?: throw Fail("fork write beyond extents")
                val n = minOf(avail, ((data.size - done) / 512).toLong()).toInt()
                if (!dev.writeSectors(lba, data.copyOfRange(done, done + n * 512))) throw Fail("write error")
                done += n * 512
            }
        }

        // ── B-tree engine
        inner class Tree(val fork: Fork, val compare: ((ByteArray, ByteArray) -> Int)?) {
            val nodeSize: Int = u16(readFork(fork, 0, 512), 14 + 18).also {
                if (it < 512 || it % 512 != 0) throw Fail("bad B-tree node size")
            }
            private val nodes = HashMap<Int, ByteArray>()
            private val dirtyNodes = sortedSetOf<Int>()
            fun flush() { for (n in dirtyNodes) writeFork(fork, n.toLong() * nodeSize, node(n)); dirtyNodes.clear() }
            fun firstLeafNum() = firstLeaf()

        fun node(n: Int): ByteArray = nodes.getOrPut(n) { readFork(fork, n.toLong() * nodeSize, nodeSize) }
        fun touch(n: Int) { dirtyNodes.add(n) }
        private fun hdr() = node(0)
        private fun root() = u32(hdr(), 14 + 2).toInt()
        private fun setRoot(v: Int) { p32(hdr(), 14 + 2, v.toLong()); touch(0) }
        private fun depth() = u16(hdr(), 14)
        private fun setDepth(v: Int) { p16(hdr(), 14, v); touch(0) }
        private fun addLeafRecords(d: Int) { p32(hdr(), 14 + 6, u32(hdr(), 14 + 6) + d); touch(0) }
        private fun firstLeaf() = u32(hdr(), 14 + 10).toInt()
        private fun lastLeaf() = u32(hdr(), 14 + 14).toInt()
        private fun setFirstLeaf(v: Int) { p32(hdr(), 14 + 10, v.toLong()); touch(0) }
        private fun setLastLeaf(v: Int) { p32(hdr(), 14 + 14, v.toLong()); touch(0) }

        fun kind(n: ByteArray) = n[8].toInt()
        fun records(n: ByteArray): MutableList<ByteArray> {
            val cnt = u16(n, 10)
            val offs = IntArray(cnt + 1) { u16(n, nodeSize - 2 * (it + 1)) }
            return MutableList(cnt) { n.copyOfRange(offs[it], offs[it + 1]) }
        }
        fun fits(recs: List<ByteArray>) = 14 + recs.sumOf { it.size } + 2 * (recs.size + 1) <= nodeSize
        fun encode(n: ByteArray, recs: List<ByteArray>) {
            java.util.Arrays.fill(n, 14, nodeSize, 0)
            var off = 14
            p16(n, 10, recs.size)
            for ((i, r) in recs.withIndex()) {
                System.arraycopy(r, 0, n, off, r.size)
                p16(n, nodeSize - 2 * (i + 1), off); off += r.size
            }
            p16(n, nodeSize - 2 * (recs.size + 1), off)
        }

        // node allocation from the header node's map record
        private fun allocNode(): Int {
            val h = hdr()
            val total = u32(h, 14 + 22).toInt()
            val mapOff = 14 + 106 + 128; val mapLen = nodeSize - 256
            for (i in 0 until minOf(total, mapLen * 8)) {
                val b = mapOff + i / 8; val bit = 0x80 shr (i % 8)
                if (h[b].toInt() and bit == 0) {
                    h[b] = (h[b].toInt() or bit).toByte()
                    p32(h, 14 + 26, u32(h, 14 + 26) - 1); touch(0)
                    val fresh = ByteArray(nodeSize); nodes[i] = fresh; touch(i)
                    return i
                }
            }
            throw Fail("catalog B-tree is full (it needs to be extended on a Mac)")
        }
        private fun freeNode(i: Int) {
            val h = hdr(); val mapOff = 14 + 106 + 128
            val b = mapOff + i / 8
            h[b] = (h[b].toInt() and (0x80 shr (i % 8)).inv()).toByte()
            p32(h, 14 + 26, u32(h, 14 + 26) + 1); touch(0)
            java.util.Arrays.fill(node(i), 0.toByte()); touch(i)
        }


        fun locate(key: ByteArray): Loc {
            val path = ArrayList<Step>()
            var n = root(); var guard = 0
            while (guard++ < 32) {
                val nb = node(n); val recs = records(nb)
                if (kind(nb) == KIND_LEAF) {
                    var pos = recs.size; var found = false
                    for (i in recs.indices) {
                        val c = compare!!(recs[i], key)
                        if (c == 0) { pos = i; found = true; break }
                        if (c > 0) { pos = i; break }
                    }
                    return Loc(path, n, pos, found)
                }
                var pick = 0
                for (i in recs.indices) if (compare!!(recs[i], key) <= 0) pick = i else break
                path.add(Step(n, pick))
                n = u32(recs[pick], keySize(recs[pick])).toInt()
            }
            throw Fail("catalog tree too deep (corrupt?)")
        }

        fun find(key: ByteArray): ByteArray? {
            val loc = locate(key)
            return if (loc.found) records(node(loc.leaf))[loc.pos] else null
        }

        // ── insert / delete ──────────────────────────────────────────────────
        fun insert(rec: ByteArray) {
            val loc = locate(rec)
            if (loc.found) throw Fail("record already exists")
            insertInto(loc.leaf, loc.pos, rec, loc.path)
            addLeafRecords(1)
        }

        private fun indexRec(key: ByteArray, child: Int): ByteArray {
            val ks = keySize(key)
            val r = ByteArray(ks + 4); System.arraycopy(key, 0, r, 0, ks); p32(r, ks, child.toLong()); return r
        }

        private fun insertInto(n: Int, pos: Int, rec: ByteArray, path: List<Step>) {
            val nb = node(n); val recs = records(nb); recs.add(pos, rec)
            if (fits(recs)) { encode(nb, recs); touch(n); return }
            // split
            val m = allocNode(); val mb = node(m)
            var acc = 0; var k = 0
            val half = recs.sumOf { it.size } / 2
            while (k < recs.size - 1 && acc + recs[k].size <= half) { acc += recs[k].size; k++ }
            if (k == 0) k = 1
            val left = recs.subList(0, k).toList(); val right = recs.subList(k, recs.size).toList()
            if (!fits(left) || !fits(right)) throw Fail("record too large to split")
            val oldF = u32(nb, 0).toInt()
            mb[8] = nb[8]; mb[9] = nb[9]
            p32(mb, 0, oldF.toLong()); p32(mb, 4, n.toLong())
            p32(nb, 0, m.toLong())
            if (oldF != 0) { p32(node(oldF), 4, m.toLong()); touch(oldF) }
            if (kind(nb) == KIND_LEAF && lastLeaf() == n) setLastLeaf(m)
            encode(nb, left); encode(mb, right); touch(n); touch(m)
            val newIdx = indexRec(right[0], m)
            if (path.isEmpty()) {
                val r = allocNode(); val rb = node(r)
                rb[8] = KIND_INDEX.toByte(); rb[9] = (nb[9] + 1).toByte()
                encode(rb, listOf(indexRec(left[0], n), newIdx)); touch(r)
                setRoot(r); setDepth(depth() + 1)
            } else {
                val parent = path.last()
                insertInto(parent.node, parent.idx + 1, newIdx, path.subList(0, path.size - 1))
            }
        }

        fun delete(key: ByteArray): ByteArray {
            val loc = locate(key)
            if (!loc.found) throw Fail("record not found")
            return deleteLoc(loc.path, loc.leaf, loc.pos)
        }

        /** Delete record [pos] of leaf node [leaf] (path found by walking the index nodes; no key comparisons). */
        fun deleteAt(leaf: Int, pos: Int): ByteArray = deleteLoc(pathTo(leaf), leaf, pos)

        fun pathTo(target: Int): List<Step> {
            if (target == root()) return emptyList()
            val acc = ArrayList<Step>()
            fun dfs(n: Int): Boolean {
                val nb = node(n)
                if (kind(nb) == KIND_LEAF) return false
                val recs = records(nb)
                for (i in recs.indices) {
                    val child = u32(recs[i], keySize(recs[i])).toInt()
                    acc.add(Step(n, i))
                    if (child == target || dfs(child)) return true
                    acc.removeAt(acc.size - 1)
                }
                return false
            }
            if (!dfs(root())) throw Fail("node $target not reachable from the tree root")
            return acc
        }

        private fun deleteLoc(path: List<Step>, leaf: Int, pos: Int): ByteArray {
            val nb = node(leaf); val recs = records(nb)
            val removed = recs.removeAt(pos)
            addLeafRecords(-1)
            if (recs.isEmpty() && leaf != root()) removeNode(leaf, path)
            else {
                encode(nb, recs); touch(leaf)
                if (pos == 0 && recs.isNotEmpty() && path.isNotEmpty()) {   // keep the parent's index key in step
                    val pp = path.last(); val pb = node(pp.node); val pr = records(pb)
                    pr[pp.idx] = indexRec(recs[0], leaf)
                    if (fits(pr)) { encode(pb, pr); touch(pp.node) }
                }
            }
            return removed
        }

        private fun removeNode(n: Int, path: List<Step>) {
            val nb = node(n)
            val prev = u32(nb, 4).toInt(); val next = u32(nb, 0).toInt()
            if (prev != 0) { p32(node(prev), 0, next.toLong()); touch(prev) }
            if (next != 0) { p32(node(next), 4, prev.toLong()); touch(next) }
            if (kind(nb) == KIND_LEAF) {
                if (firstLeaf() == n) setFirstLeaf(next)
                if (lastLeaf() == n) setLastLeaf(prev)
            }
            freeNode(n)
            if (path.isEmpty()) return
            val p = path.last(); val pb = node(p.node); val pr = records(pb)
            pr.removeAt(p.idx)
            if (pr.isEmpty() && p.node != root()) { removeNode(p.node, path.subList(0, path.size - 1)) }
            else { encode(pb, pr); touch(p.node) }
        }

        }

        // ── keys ─────────────────────────────────────────────────────────────
        fun key(parent: Long, name: String): ByteArray {
            val chars = nfd(name)
            val k = ByteArray(8 + 2 * chars.length)
            p16(k, 0, 6 + 2 * chars.length); p32(k, 2, parent); p16(k, 6, chars.length)
            for ((i, c) in chars.withIndex()) p16(k, 8 + 2 * i, c.code)
            return k
        }
        fun nfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        fun keySize(rec: ByteArray) = 2 + u16(rec, 0)
        fun keyParent(rec: ByteArray) = u32(rec, 2)
        fun keyName(rec: ByteArray): String {
            val n = u16(rec, 6); val sb = StringBuilder(n)
            for (i in 0 until n) sb.append(u16(rec, 8 + 2 * i).toChar())
            return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
        }
        private fun fold(c: Int): Int = if (caseSensitive) c else Character.toLowerCase(c.toChar()).code
        fun compare(a: ByteArray, b: ByteArray): Int {
            val pa = keyParent(a); val pb = keyParent(b)
            if (pa != pb) return if (pa < pb) -1 else 1
            val la = u16(a, 6); val lb = u16(b, 6)
            var i = 0; var j = 0
            while (true) {
                while (!caseSensitive && i < la && fold(u16(a, 8 + 2 * i)) == 0) i++
                while (!caseSensitive && j < lb && fold(u16(b, 8 + 2 * j)) == 0) j++
                if (i >= la || j >= lb) return if (i >= la && j >= lb) 0 else if (i >= la) -1 else 1
                val ca = fold(u16(a, 8 + 2 * i)); val cb = fold(u16(b, 8 + 2 * j))
                if (ca != cb) return if (ca < cb) -1 else 1
                i++; j++
            }
        }

        // ── catalog tree access ──────────────────────────────────────────
        fun find(key: ByteArray): ByteArray? = cat.find(key)
        val attrTree: Tree? by lazy { if (attrFork.total > 0) Tree(attrFork, null) else null }

        // ── catalog records ──────────────────────────────────────────────────
        fun thread(cnid: Long, parent: Long, name: String, isDir: Boolean): ByteArray {
            val chars = nfd(name)
            val k = key(cnid, "")
            val d = ByteArray(10 + 2 * chars.length)
            p16(d, 0, if (isDir) 3 else 4); p32(d, 4, parent); p16(d, 8, chars.length)
            for ((i, c) in chars.withIndex()) p16(d, 10 + 2 * i, c.code)
            return k + d
        }

        fun folderData(cnid: Long, now: Long): ByteArray {
            val d = ByteArray(88)
            p16(d, 0, 1); p16(d, 2, 0); p32(d, 4, 0); p32(d, 8, cnid)   // folder flags: bit 1 (thread exists) is invalid for folders
            for (o in intArrayOf(12, 16, 20, 24)) p32(d, o, now)
            p32(d, 32, 99); p32(d, 36, 99); p16(d, 42, 0x41ED)   // owner/group "unknown", drwxr-xr-x
            return d
        }

        fun fileData(cnid: Long, now: Long, size: Long, ext: List<Ext>): ByteArray {
            val d = ByteArray(248)
            p16(d, 0, 2); p16(d, 2, 0x0002); p32(d, 8, cnid)
            for (o in intArrayOf(12, 16, 20, 24)) p32(d, o, now)
            p32(d, 32, 99); p32(d, 36, 99); p16(d, 42, 0x81A4)   // -rw-r--r--
            p64(d, 88, size); p32(d, 96, 0)
            p32(d, 100, ext.sumOf { it.count })
            for ((i, e) in ext.withIndex()) { p32(d, 104 + i * 8, e.start); p32(d, 108 + i * 8, e.count) }
            return d
        }

        private fun validName(name: String) =
            name.isNotEmpty() && nfd(name).length <= 255 && name.none { it == '/' || it == ':' || it.code == 0 }

        /** Folder record for [cnid], located through its thread record. Returns (key, record). */
        private fun folderOf(cnid: Long): Pair<ByteArray, ByteArray> {
            val t = find(key(cnid, "")) ?: throw Fail("parent folder $cnid not found")
            val ks = keySize(t)
            if (u16(t, ks) != 3) throw Fail("parent is not a folder")
            val parent = u32(t, ks + 4)
            val nameLen = u16(t, ks + 8)
            val sb = StringBuilder(); for (i in 0 until nameLen) sb.append(u16(t, ks + 10 + 2 * i).toChar())
            val k = key(parent, sb.toString())
            val rec = find(k) ?: throw Fail("folder record for $cnid missing")
            return k to rec
        }

        private fun bumpParent(parentCnid: Long, delta: Int) {
            val (k, _) = folderOf(parentCnid)
            val loc = cat.locate(k)
            val nb = cat.node(loc.leaf); val recs = cat.records(nb)
            val r = recs[loc.pos]; val o = keySize(r)
            p32(r, o + 4, u32(r, o + 4) + delta)
            val now = nowHfs(); p32(r, o + 16, now); p32(r, o + 20, now)
            cat.encode(nb, recs); cat.touch(loc.leaf)
        }

        fun createEntry(parentCnid: Long, name: String, isDir: Boolean, size: Long, ext: List<Ext>) {
            if (!validName(name)) throw Fail("invalid name")
            folderOf(parentCnid)                                          // parent must exist
            val k = key(parentCnid, name)
            if (find(k) != null) throw Fail("'$name' already exists")
            val cnid = nextCnid++
            val now = nowHfs()
            val data = if (isDir) folderData(cnid, now) else fileData(cnid, now, size, ext)
            cat.insert(k + data)
            cat.insert(thread(cnid, parentCnid, name, isDir))
            bumpParent(parentCnid, +1)
            if (isDir) folderCount++ else fileCount++
        }

        fun deleteByKey(parentCnid: Long, name: String, expectCnid: Long) {
            val rec = find(key(parentCnid, name)) ?: throw Fail("'$name' not found")
            deleteRecord(parentCnid, name, rec, expectCnid)
            bumpParent(parentCnid, -1)
        }

        private fun deleteRecord(parentCnid: Long, name: String, rec: ByteArray, expectCnid: Long) {
            val o = keySize(rec)
            val type = u16(rec, o)
            val flags = u16(rec, o + 2)
            if (flags and 0x000C != 0) deleteAttributes(u32(rec, o + 8))   // extended attributes / ACL
            if (type == 1) {
                val cnid = u32(rec, o + 8)
                if (expectCnid != 0L && cnid != expectCnid) throw Fail("catalog entry changed")
                // children
                for ((cn, cname, crec) in children(cnid)) deleteRecord(cnid, cname, crec, 0)
                cat.delete(key(parentCnid, name)); cat.delete(key(cnid, ""))
                folderCount--
            } else if (type == 2) {
                val cnid = u32(rec, o + 8)
                if (expectCnid != 0L && cnid != expectCnid) throw Fail("catalog entry changed")
                for (forkOff in intArrayOf(o + 88, o + 168)) {
                    val f = parseFork(rec, forkOff)
                    if (f.total != f.ext.sumOf { it.count }) throw Fail("'$name' uses overflow extents")
                    for (e in f.ext) freeRun(e.start, e.count)
                }
                cat.delete(key(parentCnid, name))
                try { cat.delete(key(cnid, "")) } catch (e: Fail) { /* file thread is optional */ }
                fileCount--
            } else throw Fail("unsupported record type $type")
        }

        /** Remove every attribute record of [cnid] (inline attributes only) from the attributes B-tree. */
        private fun deleteAttributes(cnid: Long) {
            val t = attrTree ?: return
            while (true) {
                var leaf = t.firstLeafNum(); var hit: Pair<Int, Int>? = null
                scan@ while (leaf != 0) {
                    val nb = t.node(leaf); val recs = t.records(nb)
                    for ((i, r) in recs.withIndex()) {
                        val fid = u32(r, 4)
                        if (fid == cnid) {
                            if (u32(r, keySize(r)) != 0x10L) throw Fail("an extended attribute is stored in separate blocks - delete it on a Mac")
                            hit = leaf to i; break@scan
                        }
                        if (fid > cnid) break@scan
                    }
                    leaf = u32(nb, 0).toInt()
                }
                if (hit == null) return
                t.deleteAt(hit.first, hit.second)
            }
        }

        private fun children(cnid: Long): List<Triple<Long, String, ByteArray>> {
            val out = ArrayList<Triple<Long, String, ByteArray>>()
            val loc = cat.locate(key(cnid, ""))
            var n = loc.leaf; var pos = loc.pos
            while (n != 0) {
                val nb = cat.node(n); val recs = cat.records(nb)
                while (pos < recs.size) {
                    val r = recs[pos]
                    if (keyParent(r) > cnid) return out
                    if (keyParent(r) == cnid && u16(r, 6) > 0) {
                        val t = u16(r, keySize(r))
                        if (t == 1 || t == 2) out.add(Triple(u32(r, keySize(r) + 8), keyName(r), r))
                    }
                    pos++
                }
                n = u32(nb, 0).toInt(); pos = 0
            }
            return out
        }

        fun rename(parentCnid: Long, oldName: String, expectCnid: Long, newName: String) {
            if (!validName(newName)) throw Fail("invalid name")
            val oldKey = key(parentCnid, oldName)
            val rec = find(oldKey) ?: throw Fail("'$oldName' not found")
            val o = keySize(rec); val type = u16(rec, o)
            if (type != 1 && type != 2) throw Fail("unsupported record type")
            val cnid = u32(rec, o + 8)
            if (expectCnid != 0L && cnid != expectCnid) throw Fail("catalog entry changed")
            val data = rec.copyOfRange(o, rec.size)
            cat.delete(oldKey); cat.delete(key(cnid, ""))
            val nk = key(parentCnid, newName)
            if (find(nk) != null) throw Fail("'$newName' already exists")
            p32(data, 20, nowHfs())
            cat.insert(nk + data)
            cat.insert(thread(cnid, parentCnid, newName, type == 1))
            bumpParent(parentCnid, 0)
        }

        // ── allocation bitmap ────────────────────────────────────────────────
        private val pageSize = 4096
        private fun page(p: Int): ByteArray = bitPages.getOrPut(p) { readFork(allocFork, p.toLong() * pageSize, pageSize) }
        private fun bit(b: Long): Boolean = page((b / 8 / pageSize).toInt())[((b / 8) % pageSize).toInt()].toInt() and (0x80 shr (b % 8).toInt()) != 0
        private fun setBit(b: Long, v: Boolean) {
            val p = (b / 8 / pageSize).toInt(); val pg = page(p); val i = ((b / 8) % pageSize).toInt(); val m = 0x80 shr (b % 8).toInt()
            pg[i] = (if (v) pg[i].toInt() or m else pg[i].toInt() and m.inv()).toByte(); dirtyPages.add(p)
        }
        private fun freeRun(start: Long, count: Long) {
            for (b in start until start + count) setBit(b, false)
            freeBlocks += count
            if (start < nextAlloc) nextAlloc = start
        }

        fun allocate(size: Long): List<Ext> {
            val need = (size + bs - 1) / bs
            if (need == 0L) return emptyList()
            if (need > freeBlocks) throw Fail("not enough free space")
            val out = ArrayList<Ext>(); var remaining = need
            var b = if (nextAlloc in 0 until totalBlocks) nextAlloc else 0L
            var wrapped = false
            while (remaining > 0) {
                if (b >= totalBlocks) { if (wrapped) throw Fail("volume too fragmented for this writer (needs overflow extents)"); wrapped = true; b = 0 }
                if (bit(b)) { b++; continue }
                var e = b
                while (e < totalBlocks && !bit(e) && e - b < remaining) e++
                if (out.size == 8) throw Fail("volume too fragmented for this writer (needs overflow extents)")
                out.add(Ext(b, e - b)); remaining -= e - b; b = e
            }
            for (e in out) for (x in e.start until e.start + e.count) setBit(x, true)
            freeBlocks -= need
            nextAlloc = out.last().start + out.last().count
            return out
        }

        fun streamToExtents(ext: List<Ext>, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?) {
            val spb = bs / 512
            var written = 0L
            val buf = ByteArray(1 shl 20)
            for (e in ext) {
                var off = 0L; val runBytes = e.count * bs
                while (off < runBytes) {
                    val want = minOf(buf.size.toLong(), runBytes - off).toInt()
                    val real = minOf(want.toLong(), size - written).toInt()
                    var got = 0
                    while (got < real) { val r = input.read(buf, got, real - got); if (r < 0) break; got += r }
                    if (got < real) throw Fail("source ended early")
                    java.util.Arrays.fill(buf, got, want, 0)
                    if (!dev.writeSectors(partitionStartLba + e.start * spb + off / 512, buf.copyOf(want))) throw Fail("write error")
                    off += want; written += got
                    onProgress?.invoke(written)
                }
            }
        }

        // ── commit ───────────────────────────────────────────────────────────
        fun commit() {
            cat.flush(); attrTree?.flush()
            for (p in dirtyPages) writeFork(allocFork, p.toLong() * pageSize, bitPages[p]!!)
            val now = nowHfs()
            p32(vh, 20, now)                       // modifyDate
            p32(vh, 32, fileCount); p32(vh, 36, folderCount)
            p32(vh, 48, freeBlocks); p32(vh, 52, nextAlloc); p32(vh, 64, nextCnid)
            p32(vh, 68, u32(vh, 68) + 1)           // writeCount
            if (!dev.writeSectors(partitionStartLba + 2, vh)) throw Fail("cannot write volume header")
            // alternate volume header: 1024 bytes before the end of the volume
            val altByte = totalBlocks * bs - 1024
            val altLba = partitionStartLba + altByte / 512
            val alt = dev.readSectors(altLba, 1)
            if (alt != null && u16(alt, 0) == u16(vh, 0)) dev.writeSectors(altLba, vh)
            dev.flushCache()
        }
    }
}
