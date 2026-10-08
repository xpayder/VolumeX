package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo

class ApfsReader(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {
    companion object {
        private const val TAG = "VolumeX"
    }

    private var containerSb: ApfsContainerSuperblock? = null
    private val btreeParser get() = containerSb?.let {
        ApfsBTreeParser(reader, partitionStartLba, it.blockSize)
    }

    override fun mount(): Boolean {
        val parser = ApfsBTreeParser(reader, partitionStartLba, 4096L) // default block size
        val data = parser.readBlock(0) ?: run {
            Log.e(TAG, "Failed to read APFS block 0")
            return false
        }
        val sb = ApfsContainerSuperblockParser.parse(data) ?: run {
            Log.e(TAG, "Not a valid APFS container")
            return false
        }
        containerSb = sb
        Log.i(TAG, "APFS mounted: ${sb.volumeCount} volume(s), blockSize=${sb.blockSize}")
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val sb = containerSb ?: return emptyList()
        val btree = ApfsBTreeParser(reader, partitionStartLba, sb.blockSize)
        val results = mutableListOf<VolumeInfo>()

        // Parse container OMAP to resolve volume OIDs
        val omapData = btree.readBlock(sb.omapOid) ?: return emptyList()
        val omap = ApfsOMapParser.parse(omapData) ?: return emptyList()

        for (volOid in sb.fsOids) {
            if (volOid == 0L) continue
            val volPaddr = btree.omapLookup(omap.treeOid, volOid) ?: continue
            val volData = btree.readBlock(volPaddr) ?: continue
            val volSb = ApfsVolumeSuperblockParser.parse(volData) ?: continue

            results.add(VolumeInfo(
                name = volSb.volumeName,
                type = "APFS",
                uuid = volSb.volUuidString,
                totalBlocks = sb.blockCount,
                blockSize = sb.blockSize,
                isEncrypted = volSb.isEncrypted,
                numFiles = volSb.numFiles,
                numDirectories = volSb.numDirectories
            ))
        }
        return results
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val sb = containerSb ?: return emptyList()
        val btree = ApfsBTreeParser(reader, partitionStartLba, sb.blockSize)

        // Parse container OMAP
        val omapData = btree.readBlock(sb.omapOid) ?: return emptyList()
        val containerOmap = ApfsOMapParser.parse(omapData) ?: return emptyList()

        // Resolve volume
        val volOids = sb.fsOids.filter { it != 0L }
        if (volumeIndex >= volOids.size) return emptyList()
        val volOid = volOids[volumeIndex]
        val volPaddr = btree.omapLookup(containerOmap.treeOid, volOid) ?: return emptyList()
        val volData = btree.readBlock(volPaddr) ?: return emptyList()
        val volSb = ApfsVolumeSuperblockParser.parse(volData) ?: return emptyList()

        // Parse volume OMAP
        val volOmapData = btree.readBlock(volSb.omapOid) ?: return emptyList()
        val volOmap = ApfsOMapParser.parse(volOmapData) ?: return emptyList()

        // Resolve filesystem tree root
        val fsRootPaddr = btree.omapLookup(volOmap.treeOid, volSb.rootTreeOid) ?: run {
            Log.e(TAG, "Could not resolve fs tree OID=${volSb.rootTreeOid}")
            return emptyList()
        }

        // Scan the filesystem tree
        val fsResults = btree.scanFsTree(fsRootPaddr)

        // Determine the target directory inode OID from path
        val targetParentOid = resolvePathOid(fsResults, path)

        return fsResults
            .filter { it.parentOid == targetParentOid }
            .map { result ->
                FileSystemEntry(
                    name = result.name,
                    path = if (path == "/") "/${result.name}" else "$path/${result.name}",
                    isDirectory = result.inode.isDirectory,
                    size = result.inode.uncompressedSize,
                    createdAt = result.inode.createTimeMs,
                    modifiedAt = result.inode.modTimeMs,
                    inodeOid = result.oid,
                    parentOid = result.parentOid,
                    extents = result.extents
                )
            }
    }

    private fun resolvePathOid(entries: List<FsTreeResult>, path: String): Long {
        if (path == "/" || path.isEmpty()) return ApfsConstants.ROOT_DIR_INO_NUM
        val parts = path.trim('/').split("/")
        var currentOid = ApfsConstants.ROOT_DIR_INO_NUM
        for (part in parts) {
            val entry = entries.find { it.parentOid == currentOid && it.name == part } ?: return ApfsConstants.ROOT_DIR_INO_NUM
            currentOid = entry.oid
        }
        return currentOid
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val sb = containerSb ?: return null
        val btree = ApfsBTreeParser(reader, partitionStartLba, sb.blockSize)
        val sortedExtents = entry.extents.sortedBy { it.logicalAddr }
        if (sortedExtents.isEmpty()) return ByteArray(0)

        val totalSize = entry.size.coerceAtLeast(0L).coerceAtMost(100 * 1024 * 1024L) // max 100MB
        val result = ByteArray(totalSize.toInt())
        var written = 0

        for (extent in sortedExtents) {
            if (written >= totalSize) break
            val physBlock = extent.physBlockNum
            val extentLen = extent.length.coerceAtMost(totalSize - written)
            val blocksNeeded = (extentLen + sb.blockSize - 1) / sb.blockSize

            for (blockIdx in 0 until blocksNeeded) {
                if (written >= totalSize) break
                val blockData = btree.readBlock(physBlock + blockIdx) ?: break
                val toCopy = minOf(sb.blockSize.toInt(), (totalSize - written).toInt())
                System.arraycopy(blockData, 0, result, written, toCopy)
                written += toCopy
            }
        }

        return if (written > 0) result.copyOf(written) else ByteArray(0)
    }

    override fun unmount() {
        containerSb = null
    }
}
