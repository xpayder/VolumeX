package com.fatalpuppet.volumex.storage.filesystem.ext

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExtReader(
    private val blockDevice: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {

    companion object {
        private const val TAG = "VolumeX"
        /** Superblock is always at byte offset 1024 from partition start. */
        private const val SUPERBLOCK_OFFSET_BYTES = 1024L
    }

    private var sb: ExtSuperblock? = null

    // ── Mount ─────────────────────────────────────────────────────────────────

    override fun mount(): Boolean {
        // The superblock starts at byte 1024 from the partition start.
        // With 512-byte sectors: byte 1024 = sector 2 (0-indexed), byte offset 0.
        val sectorSize = blockDevice.sectorSize()
        val sbSector = partitionStartLba + SUPERBLOCK_OFFSET_BYTES / sectorSize
        val sbOffsetInSector = (SUPERBLOCK_OFFSET_BYTES % sectorSize).toInt()

        // Read two sectors to cover the 1024-byte superblock
        val s0 = blockDevice.readSector(sbSector) ?: return false.also { Log.e(TAG, "ext: failed to read SB sector") }
        val s1 = blockDevice.readSector(sbSector + 1) ?: ByteArray(sectorSize)

        val sbData = ByteArray(1024)
        val copyFromS0 = minOf(sectorSize - sbOffsetInSector, 1024)
        System.arraycopy(s0, sbOffsetInSector, sbData, 0, copyFromS0)
        if (copyFromS0 < 1024) {
            System.arraycopy(s1, 0, sbData, copyFromS0, 1024 - copyFromS0)
        }

        sb = ExtSuperblockParser.parse(sbData) ?: return false.also { Log.e(TAG, "ext: invalid superblock magic") }
        Log.i(TAG, "ext: mounted vol='${sb!!.volumeName}' blockSize=${sb!!.blockSize} inodeSize=${sb!!.inodeSize}")
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val s = sb ?: return emptyList()
        val totalBytes = s.blocksCount * s.blockSize
        val freeBytes = s.freeBlocks * s.blockSize
        return listOf(
            VolumeInfo(
                name = s.volumeName.ifEmpty { "ext4 Volume" },
                type = when {
                    s.hasExtents -> "ext4"
                    else -> "ext2/3"
                },
                uuid = "",
                totalBlocks = s.blocksCount,
                blockSize = s.blockSize.toLong(),
                freeBlocks = s.freeBlocks,
                numFiles = s.inodesCount - s.freeInodes
            )
        )
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val s = sb ?: return emptyList()
        val dirIno = if (path == "/" || path.isEmpty()) {
            ExtConstants.EXT2_ROOT_INO.toLong()
        } else {
            resolvePathInode(path) ?: return emptyList()
        }
        val dirData = readInodeData(dirIno) ?: return emptyList()
        return parseDirBlock(dirData, path, s)
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val data = readInodeData(entry.inodeOid) ?: return null
        return if (entry.size > 0) data.copyOf(entry.size.coerceAtMost(data.size.toLong()).toInt()) else data
    }

    override fun searchFiles(query: String, volumeIndex: Int): List<FileSystemEntry> {
        val results = mutableListOf<FileSystemEntry>()
        searchRecursive("/", query.lowercase(), results, depth = 0, maxDepth = 12)
        return results
    }

    override fun unmount() {
        sb = null
    }

    // ── Block I/O ─────────────────────────────────────────────────────────────

    private fun readBlock(blockNum: Long): ByteArray? {
        val s = sb ?: return null
        if (blockNum == 0L) return ByteArray(s.blockSize) // sparse block
        val sectorSize = blockDevice.sectorSize()
        val sectorsPerBlock = s.blockSize / sectorSize
        val firstSector = partitionStartLba + blockNum * sectorsPerBlock
        val blockData = ByteArray(s.blockSize)
        var offset = 0
        for (i in 0 until sectorsPerBlock) {
            val sectorData = blockDevice.readSector(firstSector + i) ?: return null
            sectorData.copyInto(blockData, offset)
            offset += sectorData.size
        }
        return blockData
    }

    // ── Inode resolution ──────────────────────────────────────────────────────

    private fun inodeToBlockGroup(inoNum: Long): Long = (inoNum - 1) / sb!!.inodesPerGroup

    private fun inodeIndex(inoNum: Long): Long = (inoNum - 1) % sb!!.inodesPerGroup

    private fun getInodeTableBlock(group: Long): Long {
        val s = sb!!
        // Block Group Descriptor Table starts at block (firstDataBlock + 1)
        val bgdtBlock = s.firstDataBlock + 1
        val bgdtData = readBlock(bgdtBlock) ?: return 0L
        val descOffset = (group * s.groupDescSize).toInt()
        if (descOffset + s.groupDescSize > bgdtData.size) return 0L
        val buf = ByteBuffer.wrap(bgdtData, descOffset, s.groupDescSize).order(ByteOrder.LITTLE_ENDIAN)
        val inoTableLo = buf.getInt(ExtConstants.BGD_INODE_TABLE_LO).toLong() and 0xFFFFFFFFL
        return if (s.has64bit && s.groupDescSize >= 64) {
            val inoTableHi = buf.getInt(descOffset + 40).toLong() and 0xFFFFFFFFL
            (inoTableHi shl 32) or inoTableLo
        } else inoTableLo
    }

    private fun readRawInode(inoNum: Long): ByteArray? {
        val s = sb ?: return null
        val group = inodeToBlockGroup(inoNum)
        val index = inodeIndex(inoNum)
        val inoTableBlock = getInodeTableBlock(group)
        if (inoTableBlock == 0L) return null

        val byteOffset = index * s.inodeSize
        val sectorSize = blockDevice.sectorSize()
        val sectorsPerBlock = s.blockSize / sectorSize
        val blockOffset = (byteOffset / s.blockSize).toInt()
        val inodeBlockNum = inoTableBlock + blockOffset
        val inodeInBlockOffset = (byteOffset % s.blockSize).toInt()

        val blockData = readBlock(inodeBlockNum) ?: return null
        if (inodeInBlockOffset + s.inodeSize > blockData.size) return null
        return blockData.copyOfRange(inodeInBlockOffset, inodeInBlockOffset + s.inodeSize)
    }

    // ── Data block reading ─────────────────────────────────────────────────────

    private fun readInodeData(inoNum: Long): ByteArray? {
        val s = sb ?: return null
        val inoData = readRawInode(inoNum) ?: return null
        val buf = ByteBuffer.wrap(inoData).order(ByteOrder.LITTLE_ENDIAN)

        val flags = buf.getInt(ExtConstants.INODE_FLAGS)
        val sizeLo = buf.getInt(ExtConstants.INODE_SIZE_LO).toLong() and 0xFFFFFFFFL
        val sizeHi = if (inoData.size > ExtConstants.INODE_SIZE_HIGH + 3) {
            buf.getInt(ExtConstants.INODE_SIZE_HIGH).toLong() and 0xFFFFFFFFL
        } else 0L
        val fileSize = (sizeHi shl 32) or sizeLo
        val maxSize = minOf(fileSize, 512L * 1024 * 1024) // cap at 512 MB

        return if (flags and ExtConstants.EXT4_EXTENTS_FL != 0) {
            readExtentData(inoData, maxSize)
        } else {
            readBlockMapData(inoData, maxSize)
        }
    }

    // ── Extent tree reading ────────────────────────────────────────────────────

    private fun readExtentData(inoData: ByteArray, maxSize: Long): ByteArray? {
        val inoBlockOffset = ExtConstants.INODE_BLOCK
        val extentData = inoData.copyOfRange(inoBlockOffset, inoBlockOffset + 60)
        val blocks = mutableListOf<ByteArray>()
        collectExtents(extentData, blocks, maxSize)
        return assembleBlocks(blocks, maxSize)
    }

    private fun collectExtents(data: ByteArray, blocks: MutableList<ByteArray>, maxSize: Long, collected: LongArray = longArrayOf(0)) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.getShort(ExtConstants.EXTENTS_HEADER_MAGIC).toInt() and 0xFFFF
        if (magic != ExtConstants.EXT4_EXT_MAGIC) return
        val numEntries = buf.getShort(ExtConstants.EXTENTS_HEADER_ENTRIES).toInt() and 0xFFFF
        val depth = buf.getShort(ExtConstants.EXTENTS_HEADER_DEPTH).toInt() and 0xFFFF

        val headerSize = 12
        for (i in 0 until numEntries) {
            if (collected[0] >= maxSize) break
            val eOffset = headerSize + i * 12
            if (eOffset + 12 > data.size) break

            if (depth == 0) {
                // Leaf extent
                val len = buf.getShort(eOffset + ExtConstants.EXTENT_LEN).toInt() and 0xFFFF
                val startHi = buf.getShort(eOffset + ExtConstants.EXTENT_START_HI).toLong() and 0xFFFFL
                val startLo = buf.getInt(eOffset + ExtConstants.EXTENT_START_LO).toLong() and 0xFFFFFFFFL
                val physBlock = (startHi shl 32) or startLo
                for (b in 0 until len) {
                    if (collected[0] >= maxSize) break
                    val bData = readBlock(physBlock + b) ?: break
                    blocks.add(bData)
                    collected[0] += bData.size
                }
            } else {
                // Index node
                val leafHi = buf.getShort(eOffset + ExtConstants.EXT_IDX_LEAF_HI).toLong() and 0xFFFFL
                val leafLo = buf.getInt(eOffset + ExtConstants.EXT_IDX_LEAF_LO).toLong() and 0xFFFFFFFFL
                val leafBlock = (leafHi shl 32) or leafLo
                val leafData = readBlock(leafBlock) ?: continue
                collectExtents(leafData, blocks, maxSize, collected)
            }
        }
    }

    // ── Block-map reading (ext2/3) ─────────────────────────────────────────────

    private fun readBlockMapData(inoData: ByteArray, maxSize: Long): ByteArray? {
        val s = sb ?: return null
        val buf = ByteBuffer.wrap(inoData).order(ByteOrder.LITTLE_ENDIAN)
        val blocks = mutableListOf<ByteArray>()
        var collected = 0L

        // Direct blocks (12)
        for (i in 0 until ExtConstants.EXT2_NDIR_BLOCKS) {
            if (collected >= maxSize) break
            val bNum = buf.getInt(ExtConstants.INODE_BLOCK + i * 4).toLong() and 0xFFFFFFFFL
            if (bNum == 0L) continue
            val bData = readBlock(bNum) ?: break
            blocks.add(bData); collected += bData.size
        }

        // Single indirect
        if (collected < maxSize) {
            val indBNum = buf.getInt(ExtConstants.INODE_BLOCK + ExtConstants.EXT2_IND_BLOCK * 4).toLong() and 0xFFFFFFFFL
            if (indBNum != 0L) {
                val indData = readBlock(indBNum)
                if (indData != null) {
                    val ibuf = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until s.blockSize / 4) {
                        if (collected >= maxSize) break
                        val bNum = ibuf.getInt().toLong() and 0xFFFFFFFFL
                        if (bNum == 0L) continue
                        val bData = readBlock(bNum) ?: break
                        blocks.add(bData); collected += bData.size
                    }
                }
            }
        }

        // Double indirect (simplified — skip if already at max)
        if (collected < maxSize) {
            val dindBNum = buf.getInt(ExtConstants.INODE_BLOCK + ExtConstants.EXT2_DIND_BLOCK * 4).toLong() and 0xFFFFFFFFL
            if (dindBNum != 0L) {
                val dindData = readBlock(dindBNum)
                if (dindData != null) {
                    val dbuf = ByteBuffer.wrap(dindData).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until s.blockSize / 4) {
                        if (collected >= maxSize) break
                        val indBNum = dbuf.getInt().toLong() and 0xFFFFFFFFL
                        if (indBNum == 0L) continue
                        val indData = readBlock(indBNum) ?: continue
                        val ibuf = ByteBuffer.wrap(indData).order(ByteOrder.LITTLE_ENDIAN)
                        for (j in 0 until s.blockSize / 4) {
                            if (collected >= maxSize) break
                            val bNum = ibuf.getInt().toLong() and 0xFFFFFFFFL
                            if (bNum == 0L) continue
                            val bData = readBlock(bNum) ?: break
                            blocks.add(bData); collected += bData.size
                        }
                    }
                }
            }
        }

        return assembleBlocks(blocks, maxSize)
    }

    private fun assembleBlocks(blocks: List<ByteArray>, maxSize: Long): ByteArray {
        val size = minOf(blocks.sumOf { it.size.toLong() }, maxSize).toInt()
        val result = ByteArray(size)
        var pos = 0
        for (block in blocks) {
            val toCopy = minOf(block.size, size - pos)
            if (toCopy <= 0) break
            block.copyInto(result, pos, 0, toCopy)
            pos += toCopy
        }
        return result
    }

    // ── Directory parsing ──────────────────────────────────────────────────────

    private fun parseDirBlock(data: ByteArray, parentPath: String, s: ExtSuperblock): List<FileSystemEntry> {
        val entries = mutableListOf<FileSystemEntry>()
        var offset = 0
        while (offset + 8 <= data.size) {
            val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            val inoNum = buf.getInt(0).toLong() and 0xFFFFFFFFL
            val recLen = buf.getShort(4).toInt() and 0xFFFF
            val nameLen = buf.get(6).toInt() and 0xFF
            // fileType at buf[7] — ignored for mode detection

            if (recLen < 8) break
            if (inoNum == 0L || nameLen == 0) { offset += recLen; continue }
            if (offset + 8 + nameLen > data.size) break

            val name = String(data, offset + ExtConstants.DIRENT_NAME, nameLen)
            if (name == "." || name == "..") { offset += recLen; continue }

            // Get inode to determine type and size
            val inoData = readRawInode(inoNum)
            val isDir: Boolean
            val size: Long
            val modTime: Long

            if (inoData != null) {
                val ibuf = ByteBuffer.wrap(inoData).order(ByteOrder.LITTLE_ENDIAN)
                val mode = ibuf.getShort(ExtConstants.INODE_MODE).toInt() and 0xFFFF
                isDir = (mode and ExtConstants.S_IFMT) == ExtConstants.S_IFDIR
                val sizeLo = ibuf.getInt(ExtConstants.INODE_SIZE_LO).toLong() and 0xFFFFFFFFL
                size = sizeLo
                val mtime = ibuf.getInt(ExtConstants.INODE_MTIME).toLong() and 0xFFFFFFFFL
                modTime = mtime * 1000L
            } else {
                isDir = false; size = 0L; modTime = 0L
            }

            val entryPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
            entries.add(
                FileSystemEntry(
                    name = name,
                    path = entryPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else size,
                    createdAt = modTime,
                    modifiedAt = modTime,
                    inodeOid = inoNum
                )
            )
            offset += recLen
        }
        return entries
    }

    // ── Path resolution ────────────────────────────────────────────────────────

    private fun resolvePathInode(path: String): Long? {
        val parts = path.trim('/').split("/")
        var currentIno = ExtConstants.EXT2_ROOT_INO.toLong()
        for (part in parts) {
            val dirData = readInodeData(currentIno) ?: return null
            val s = sb ?: return null
            var found = false
            var offset = 0
            while (offset + 8 <= dirData.size) {
                val buf = ByteBuffer.wrap(dirData, offset, dirData.size - offset).order(ByteOrder.LITTLE_ENDIAN)
                val inoNum = buf.getInt(0).toLong() and 0xFFFFFFFFL
                val recLen = buf.getShort(4).toInt() and 0xFFFF
                val nameLen = buf.get(6).toInt() and 0xFF
                if (recLen < 8) break
                if (inoNum != 0L && nameLen > 0 && offset + 8 + nameLen <= dirData.size) {
                    val name = String(dirData, offset + 8, nameLen)
                    if (name == part) {
                        currentIno = inoNum
                        found = true
                        break
                    }
                }
                offset += recLen
            }
            if (!found) return null
        }
        return currentIno
    }

    private fun searchRecursive(
        path: String,
        query: String,
        results: MutableList<FileSystemEntry>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val entries = try { listDirectory(0, path) } catch (e: Exception) { return }
        for (entry in entries) {
            if (entry.name.lowercase().contains(query)) results.add(entry)
            if (entry.isDirectory) searchRecursive(entry.path, query, results, depth + 1, maxDepth)
        }
    }
}
