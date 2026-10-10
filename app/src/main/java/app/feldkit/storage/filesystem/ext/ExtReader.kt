package app.feldkit.storage.filesystem.ext

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.VolumeInfo

/**
 * ext2 / ext3 / ext4 reader (read-only): classic block maps and extent trees, 32- and 64-bit group descriptors, flex_bg,
 * hash-tree directories (read linearly), sparse and uninitialised extents, fast symlinks, small inline-data files.
 * Files are streamed: nothing is loaded whole.
 */
class ExtReader(
    private val dev: BlockDeviceReader,
    private val startLba: Long,
) : FileSystemReader {
    companion object {
        private const val TAG = "FeldKit"
        private const val ROOT_INO = 2L
        private const val FL_EXTENTS = 0x80000
        private const val FL_INLINE = 0x10000000
    }

    private var sb: ExtSuperblock? = null
    private var bs = 1024
    private val blockCache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>?) = size > 256 }
    private val dirCache = HashMap<String, List<FileSystemEntry>>()

    // ── raw access ──

    private fun readBytes(offset: Long, len: Int): ByteArray? {
        val ss = dev.sectorSize()
        val abs = startLba * ss + offset
        val first = abs / ss; val last = (abs + len - 1) / ss
        val data = dev.readSectors(first, (last - first + 1).toInt()) ?: return null
        val skip = (abs - first * ss).toInt()
        return if (skip == 0 && data.size == len) data else data.copyOfRange(skip, skip + len)
    }

    private fun block(n: Long): ByteArray? {
        synchronized(blockCache) { blockCache[n]?.let { return it } }
        val b = readBytes(n * bs, bs) ?: return null
        synchronized(blockCache) { blockCache[n] = b }
        return b
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    // ── mount ──

    override fun mount(): Boolean {
        val raw = readBytes(1024, 1024) ?: return false
        val s = ExtSuperblockParser.parse(raw) ?: return false
        if (s.blockSize !in 1024..65536 || s.inodesPerGroup <= 0 || s.blocksPerGroup <= 0) return false
        // features we cannot read must not be mounted as if they were fine
        val unsupportedIncompat = 0x0001 or 0x0004 or 0x0010 or 0x2000 // compression, journal device, meta_bg, encrypted names/ea_inode excluded below
        if (s.featureIncompat and 0x0001 != 0) return false.also { Log.w(TAG, "ext: compressed file system not supported") }
        if (s.featureIncompat and 0x10000 != 0) return false.also { Log.w(TAG, "ext: encrypted (fscrypt) volumes not supported") }
        sb = s; bs = s.blockSize
        Log.i(TAG, "ext: '${s.volumeName}' blockSize=$bs inodeSize=${s.inodeSize} extents=${s.hasExtents} 64bit=${s.has64bit}")
        return readInode(ROOT_INO)?.let { it.mode and 0xF000 == 0x4000 } ?: false
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val s = sb ?: return emptyList()
        return listOf(
            VolumeInfo(
                name = s.volumeName.ifEmpty { if (s.hasExtents) "ext4 volume" else "ext2/3 volume" },
                type = if (s.hasExtents) "ext4" else "ext2/3",
                totalBlocks = s.blocksCount, blockSize = s.blockSize.toLong(), freeBlocks = s.freeBlocks, freeKnown = true,
                numFiles = (s.inodesCount - s.freeInodes).coerceAtLeast(0)
            )
        )
    }

    // ── inodes ──

    private class Inode(val mode: Int, val size: Long, val mtime: Long, val flags: Int, val raw: ByteArray)

    private fun readInode(ino: Long): Inode? {
        val s = sb ?: return null
        if (ino < 1) return null
        val group = (ino - 1) / s.inodesPerGroup; val index = (ino - 1) % s.inodesPerGroup
        val gdtBlock = s.firstDataBlock + 1
        val descSize = s.groupDescSize
        val perBlock = bs / descSize
        val gb = block(gdtBlock + group / perBlock) ?: return null
        val go = ((group % perBlock) * descSize).toInt()
        val lo = u32(gb, go + 8)
        val hi = if (s.has64bit && descSize >= 64) u32(gb, go + 0x28) else 0L
        val table = (hi shl 32) or lo
        val byteOff = index * s.inodeSize
        val raw = block(table + byteOff / bs)?.let { it.copyOfRange((byteOff % bs).toInt(), (byteOff % bs).toInt() + minOf(s.inodeSize, bs)) } ?: return null
        val mode = u16(raw, 0)
        val sizeLo = u32(raw, 4); val sizeHi = u32(raw, 108)
        val size = if (mode and 0xF000 == 0x8000 || mode and 0xF000 == 0x4000) (sizeHi shl 32) or sizeLo else sizeLo
        return Inode(mode, size, u32(raw, 16) * 1000L, u32(raw, 32).toInt(), raw)
    }

    // ── file block mapping ──

    private class Run(val logical: Long, val physical: Long, val count: Long, val unwritten: Boolean)

    private fun extentRuns(node: ByteArray, off: Int, out: MutableList<Run>, depth: Int = 0) {
        if (depth > 6 || u16(node, off) != 0xF30A) return
        val entries = u16(node, off + 2); val level = u16(node, off + 6)
        for (i in 0 until entries) {
            val e = off + 12 + i * 12
            if (e + 12 > node.size) break
            if (level == 0) {
                val len = u16(node, e + 4); val unwritten = len > 32768
                val count = if (unwritten) (len - 32768).toLong() else len.toLong()
                val phys = (u16(node, e + 6).toLong() shl 32) or u32(node, e + 8)
                out.add(Run(u32(node, e), phys, count, unwritten))
            } else {
                val child = u32(node, e + 4) or (u16(node, e + 8).toLong() shl 32)
                val cb = block(child) ?: continue
                extentRuns(cb, 0, out, depth + 1)
            }
        }
    }

    /** Physical block of file block [n] with classic (non-extent) block maps; 0 means a hole. */
    private fun mapBlock(raw: ByteArray, n: Long): Long {
        val ptrs = bs / 4L
        if (n < 12) return u32(raw, 40 + (n * 4).toInt())
        var m = n - 12
        fun level(base: Long, idx: Long, depth: Int): Long {
            if (base == 0L) return 0
            val b = block(base) ?: return 0
            if (depth == 0) return u32(b, (idx * 4).toInt())
            var span = 1L; repeat(depth) { span *= ptrs }
            return level(u32(b, ((idx / span) * 4).toInt()), idx % span, depth - 1)
        }
        if (m < ptrs) return level(u32(raw, 88), m, 0)
        m -= ptrs
        if (m < ptrs * ptrs) return level(u32(raw, 92), m, 1)
        m -= ptrs * ptrs
        return level(u32(raw, 96), m, 2)
    }

    private class FileMap(val inode: Inode, val runs: List<Run>?)

    private fun mapOf(ino: Inode): FileMap {
        if (ino.flags and FL_EXTENTS != 0) {
            val runs = ArrayList<Run>(); extentRuns(ino.raw, 40, runs)
            runs.sortBy { it.logical }
            return FileMap(ino, runs)
        }
        return FileMap(ino, null)
    }

    private fun physicalOf(m: FileMap, n: Long): Long {
        val runs = m.runs ?: return mapBlock(m.inode.raw, n)
        var lo = 0; var hi = runs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1; val r = runs[mid]
            if (n < r.logical) hi = mid - 1 else if (n >= r.logical + r.count) lo = mid + 1 else return if (r.unwritten) 0 else r.physical + (n - r.logical)
        }
        return 0
    }

    /** Reads [len] bytes of the file at [offset] (zeros for holes). Large contiguous stretches are fetched in one go. */
    private fun readData(m: FileMap, offset: Long, len: Int): ByteArray? {
        val ino = m.inode
        if (ino.flags and FL_INLINE != 0) {
            val n = (minOf(ino.size, 60L) - offset).coerceIn(0, len.toLong()).toInt()
            return ino.raw.copyOfRange(40 + offset.toInt(), 40 + offset.toInt() + n)
        }
        val out = ByteArray(len); var done = 0
        while (done < len) {
            val pos = offset + done; val bn = pos / bs; val inB = (pos % bs).toInt()
            val phys = physicalOf(m, bn)
            if (phys == 0L) { done += minOf(len - done, bs - inB); continue }          // hole or uninitialised extent: zeros
            // extend over following blocks that are physically contiguous
            val maxBlocks = minOf(512L, (inB + (len - done) + bs - 1L) / bs)
            var run = 1L
            while (run < maxBlocks && physicalOf(m, bn + run) == phys + run) run++
            val want = minOf((len - done).toLong(), run * bs - inB).toInt()
            val data = readBytes(phys * bs + inB, want) ?: return null
            System.arraycopy(data, 0, out, done, want); done += want
        }
        return out
    }

    // ── directories ──

    private fun dirEntries(path: String, ino: Inode): List<FileSystemEntry> {
        val m = mapOf(ino)
        val size = ino.size.coerceAtMost(64L shl 20).toInt()
        val data = readData(m, 0, size) ?: return emptyList()
        val out = ArrayList<FileSystemEntry>()
        var p = 0
        while (p + 8 <= data.size) {
            val inode = u32(data, p); val recLen = u16(data, p + 4); val nameLen = data[p + 6].toInt() and 0xFF
            if (recLen < 8 || recLen % 4 != 0 || p + recLen > data.size) { p = (p / bs + 1) * bs; continue }
            if (inode != 0L && nameLen > 0 && p + 8 + nameLen <= data.size) {
                val name = String(data, p + 8, nameLen, Charsets.UTF_8)
                if (name != "." && name != "..") {
                    val child = readInode(inode)
                    val isDir = child != null && child.mode and 0xF000 == 0x4000
                    val t = child?.mtime ?: 0L
                    val base = if (path == "/") "" else path
                    out.add(FileSystemEntry(name, "$base/$name", isDir, if (isDir || child == null) 0L else child.size, t, t, inodeOid = inode))
                }
            }
            p += recLen
        }
        return out
    }

    private fun inodeAt(path: String): Inode? {
        var ino = ROOT_INO
        var cur = readInode(ino) ?: return null
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            val list = dirEntries("/", cur)
            val hit = list.firstOrNull { it.name == part } ?: return null
            ino = hit.inodeOid; cur = readInode(ino) ?: return null
        }
        return cur
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val key = if (path.isEmpty()) "/" else path
        dirCache[key]?.let { return it }
        val ino = if (key == "/") readInode(ROOT_INO) else inodeAt(key)
        if (ino == null || ino.mode and 0xF000 != 0x4000) return emptyList()
        val list = dirEntries(key, ino)
        if (dirCache.size > 500) dirCache.clear()
        dirCache[key] = list
        return list
    }

    // ── file data ──

    private fun inodeOf(entry: FileSystemEntry): Inode? = (if (entry.inodeOid > 0) readInode(entry.inodeOid) else null) ?: inodeAt(entry.path)

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        return if (readFileTo(entry, out)) out.toByteArray() else null
    }

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val ino = inodeOf(entry) ?: return false
        if (ino.mode and 0xF000 == 0xA000 && ino.size < 60 && ino.flags and FL_EXTENTS == 0) { out.write(ino.raw, 40, ino.size.toInt()); return true }   // fast symlink
        val m = mapOf(ino)
        var off = 0L
        while (off < ino.size) {
            val n = minOf(2L shl 20, ino.size - off).toInt()
            val b = readData(m, off, n) ?: return false
            out.write(b); off += n; onProgress?.invoke(off)
        }
        return true
    }

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        val ino = inodeOf(entry) ?: return -1
        if (offset >= ino.size) return 0
        val n = minOf(len.toLong(), ino.size - offset).toInt()
        val b = readData(mapOf(ino), offset, n) ?: return -1
        System.arraycopy(b, 0, buf, bufOff, b.size)
        return b.size
    }

    override fun unmount() { blockCache.clear(); dirCache.clear(); sb = null }
}
