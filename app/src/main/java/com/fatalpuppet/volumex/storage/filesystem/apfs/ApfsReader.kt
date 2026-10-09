package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import java.io.OutputStream

class ApfsReader(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {
    companion object {
        private const val TAG = "VolumeX"

        /** APFS object checksum: Fletcher-64 over bytes [8, size) as little-endian u32 words. */
        fun checksumOk(block: ByteArray): Boolean {
            if (block.size < 16 || block.size % 4 != 0) return false
            val mod = 0xFFFFFFFFL
            var sum1 = 0L
            var sum2 = 0L
            var i = 8
            while (i < block.size) {
                val w = (block[i].toLong() and 0xFF) or ((block[i + 1].toLong() and 0xFF) shl 8) or
                    ((block[i + 2].toLong() and 0xFF) shl 16) or ((block[i + 3].toLong() and 0xFF) shl 24)
                sum1 = (sum1 + w) % mod
                sum2 = (sum2 + sum1) % mod
                i += 4
            }
            val c1 = mod - ((sum1 + sum2) % mod)
            val c2 = mod - ((sum1 + c1) % mod)
            val expected = (c2 shl 32) or c1
            var stored = 0L
            for (k in 7 downTo 0) stored = (stored shl 8) or (block[k].toLong() and 0xFF)
            return stored == expected
        }
    }

    private var containerSb: ApfsContainerSuperblock? = null

    private class Volume(
        val info: VolumeInfo,
        val sb: ApfsVolumeSuperblock,
        val byParent: Map<Long, List<FsTreeResult>>,
        val byOid: Map<Long, FsTreeResult>
    )
    private val volumes = HashMap<Int, Volume?>()
    private val stamps = HashMap<Int, Long>()

    private fun parser(blockSize: Long) = ApfsBTreeParser(reader, partitionStartLba, blockSize)

    override fun mount(): Boolean {
        val p0 = parser(4096L)
        val data = p0.readBlock(0) ?: run { Log.e(TAG, "Failed to read APFS block 0"); return false }
        var sb = ApfsContainerSuperblockParser.parse(data) ?: run { Log.e(TAG, "Not a valid APFS container"); return false }

        // Block 0 may be a stale copy: the newest valid superblock lives in the checkpoint descriptor area.
        val bs = sb.blockSize
        if (sb.xpDescBase > 0 && sb.xpDescBlocks > 0 && sb.xpDescBlocks < 100_000) {
            val p = parser(bs)
            for (i in 0 until sb.xpDescBlocks) {
                val blk = p.readBlock(sb.xpDescBase + i) ?: continue
                val cand = ApfsContainerSuperblockParser.parse(blk) ?: continue
                if (cand.header.xid > sb.header.xid && checksumOk(blk)) sb = cand
            }
        }
        containerSb = sb
        volumes.clear()
        Log.i(TAG, "APFS mounted: ${sb.fsOids.count { it != 0L }} volume(s), blockSize=${sb.blockSize}, xid=${sb.header.xid}")
        return true
    }

    /** Resolves volume [index], reading and caching its whole filesystem tree. */
    private fun volume(index: Int): Volume? {
        val cached = volumes[index]
        if (cached != null) {
            // The in-place writer does not create a new transaction, so detect changes from the volume superblock itself.
            val now = currentStamp(index)
            if (now != null && now != stamps[index]) { volumes.remove(index); return volumes.getOrPut(index) { loadVolume(index) } }
            return cached
        }
        return volumes.getOrPut(index) { loadVolume(index) }
    }

    private fun volumeSuperblock(index: Int): ApfsVolumeSuperblock? {
        val sb = containerSb ?: return null
        val btree = parser(sb.blockSize)
        val containerOmap = btree.readBlock(sb.omapOid)?.let { ApfsOMapParser.parse(it) } ?: return null
        val volOids = sb.fsOids.filter { it != 0L }
        if (index !in volOids.indices) return null
        val volPaddr = btree.omapLookup(containerOmap.treeOid, volOids[index], sb.header.xid) ?: return null
        return btree.readBlock(volPaddr)?.let { ApfsVolumeSuperblockParser.parse(it) }
    }

    private fun currentStamp(index: Int): Long? = volumeSuperblock(index)?.let { it.lastModTime xor (it.nextObjId shl 7) xor (it.numFiles shl 21) xor it.numDirectories }

    private fun loadVolume(index: Int): Volume? {
        val sb = containerSb ?: return null
        val btree = parser(sb.blockSize)
        val omapData = btree.readBlock(sb.omapOid) ?: return null
        val containerOmap = ApfsOMapParser.parse(omapData) ?: return null
        val volOids = sb.fsOids.filter { it != 0L }
        if (index !in volOids.indices) return null

        val volPaddr = btree.omapLookup(containerOmap.treeOid, volOids[index], sb.header.xid) ?: return null
        val volSb = btree.readBlock(volPaddr)?.let { ApfsVolumeSuperblockParser.parse(it) } ?: return null
        stamps[index] = volSb.lastModTime xor (volSb.nextObjId shl 7) xor (volSb.numFiles shl 21) xor volSb.numDirectories
        val volOmap = btree.readBlock(volSb.omapOid)?.let { ApfsOMapParser.parse(it) } ?: return null
        val xid = volSb.header.xid
        val results = btree.scanFsTree(volSb.rootTreeOid) { oid -> btree.omapLookup(volOmap.treeOid, oid, xid) }

        val info = VolumeInfo(
            name = volSb.volumeName, type = "APFS", uuid = volSb.volUuidString,
            totalBlocks = sb.blockCount, blockSize = sb.blockSize, isEncrypted = volSb.isEncrypted,
            numFiles = volSb.numFiles, numDirectories = volSb.numDirectories
        )
        return Volume(info, volSb, results.groupBy { it.parentOid }, results.associateBy { it.oid })
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val sb = containerSb ?: return emptyList()
        return sb.fsOids.filter { it != 0L }.indices.mapNotNull { volume(it)?.info }
    }

    private fun resolvePathOid(vol: Volume, path: String): Long? {
        var cur = ApfsConstants.ROOT_DIR_INO_NUM
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = vol.byParent[cur]?.firstOrNull { it.name == part }?.oid ?: return null
        }
        return cur
    }

    private fun toEntry(r: FsTreeResult, path: String) = FileSystemEntry(
        name = r.name,
        path = if (path == "/" || path.isEmpty()) "/${r.name}" else "${path.trimEnd('/')}/${r.name}",
        isDirectory = r.inode.isDirectory,
        size = if (r.inode.isDirectory) 0L else r.inode.uncompressedSize,
        createdAt = r.inode.createTimeMs,
        modifiedAt = r.inode.modTimeMs,
        inodeOid = r.oid,
        parentOid = r.parentOid,
        extents = r.extents
    )

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val vol = volume(volumeIndex) ?: return emptyList()
        val dirOid = resolvePathOid(vol, path) ?: return emptyList()
        return (vol.byParent[dirOid] ?: emptyList()).map { toEntry(it, path) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val size = entry.size
        if (size < 0 || size > Int.MAX_VALUE - 8) return null
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(16))
        return if (readFileTo(entry, out)) out.toByteArray() else null
    }

    /** Streams the file's bytes (honouring sparse extents) without holding the whole file in memory. */
    override fun readFileTo(entry: FileSystemEntry, out: OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val sb = containerSb ?: return false
        val bs = sb.blockSize.toInt()
        val btree = parser(sb.blockSize)
        var pos = 0L
        val total = entry.size.coerceAtLeast(0L)
        val zeros = ByteArray(bs)
        for (extent in entry.extents.sortedBy { it.logicalAddr }) {
            if (pos >= total) break
            // hole before this extent
            while (pos < extent.logicalAddr && pos < total) {
                val n = minOf((extent.logicalAddr - pos), (total - pos), bs.toLong()).toInt()
                out.write(zeros, 0, n); pos += n
            }
            var off = 0L
            while (off < extent.length && pos < total) {
                val chunk = minOf(bs.toLong(), extent.length - off, total - pos).toInt()
                if (extent.physBlockNum == 0L) {
                    out.write(zeros, 0, chunk)   // sparse extent
                } else {
                    val block = btree.readBlock(extent.physBlockNum + off / bs) ?: return false
                    out.write(block, 0, chunk)
                }
                off += chunk; pos += chunk
                onProgress?.invoke(pos)
            }
        }
        while (pos < total) {  // trailing hole
            val n = minOf(total - pos, bs.toLong()).toInt()
            out.write(zeros, 0, n); pos += n
        }
        return true
    }

    override fun rootEntry(volumeIndex: Int): FileSystemEntry = FileSystemEntry(
        name = "/", path = "/", isDirectory = true, size = 0, createdAt = 0, modifiedAt = 0,
        inodeOid = ApfsConstants.ROOT_DIR_INO_NUM
    )

    override fun unmount() {
        containerSb = null
        volumes.clear()
    }
}
