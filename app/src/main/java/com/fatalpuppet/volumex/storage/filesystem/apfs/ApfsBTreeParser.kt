package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses APFS B-Trees.
 *
 * For OMAP trees (fixed K/V, 16-byte keys and 16-byte values):
 *   - Lookup by (oid, xid) -> physical block address
 *
 * For FS trees (variable K/V):
 *   - Scan for INODE, DIR_REC, FILE_EXTENT records
 */
class ApfsBTreeParser(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val blockSize: Long
) {
    companion object {
        private const val TAG = "VolumeX"
        private const val MAX_DEPTH = 10
    }

    private val sectorsPerBlock: Long get() = blockSize / reader.sectorSize()

    /** Read one APFS block (blockSize bytes) starting at physical block address `blockAddr` */
    fun readBlock(blockAddr: Long): ByteArray? {
        val lba = partitionStartLba + blockAddr * sectorsPerBlock
        val result = ByteArray(blockSize.toInt())
        val sectorSize = reader.sectorSize()
        for (i in 0 until sectorsPerBlock) {
            val sector = reader.readSector(lba + i) ?: return null
            System.arraycopy(sector, 0, result, (i * sectorSize).toInt(), sectorSize)
        }
        return result
    }

    /**
     * Look up an OID in an OMAP B-Tree (physical tree, fixed K/V).
     * Returns the physical block address of the object, or null if not found.
     */
    fun omapLookup(omapTreeRootAddr: Long, targetOid: Long, targetXid: Long = Long.MAX_VALUE): Long? {
        return omapLookupNode(omapTreeRootAddr, targetOid, targetXid, 0)
    }

    private fun omapLookupNode(blockAddr: Long, targetOid: Long, targetXid: Long, depth: Int): Long? {
        if (depth > MAX_DEPTH) return null
        val data = readBlock(blockAddr) ?: return null
        val node = ApfsBTreeNode.parse(data) ?: return null

        if (node.nkeys == 0) return null

        if (node.isLeaf) {
            // FIXED_KV_SIZE leaf: key=omap_key_t (16 bytes), value=omap_val_t (16 bytes)
            for (i in 0 until node.nkeys) {
                val toc = node.getFixedTocEntry(i)
                val kOff = node.keyOffset(toc.k)
                if (kOff + 16 > data.size) continue
                val kBuf = ByteBuffer.wrap(data, kOff, 16).order(ByteOrder.LITTLE_ENDIAN)
                val oid = kBuf.getLong()
                val xid = kBuf.getLong()
                if (oid == targetOid && xid <= targetXid) {
                    // Value stored from end of block
                    val vOff = node.fixedValueOffset(toc.v)
                    if (vOff < 0 || vOff + 16 > data.size) continue
                    val vBuf = ByteBuffer.wrap(data, vOff, 16).order(ByteOrder.LITTLE_ENDIAN)
                    vBuf.getInt() // flags
                    vBuf.getInt() // size
                    val paddr = vBuf.getLong()
                    return paddr
                }
            }
            return null
        } else {
            // Internal node: find the right child
            // For internal nodes, values are child block addresses (uint64)
            // Binary search: find largest key <= target
            var childAddr: Long? = null
            for (i in 0 until node.nkeys) {
                val toc = node.getFixedTocEntry(i)
                val kOff = node.keyOffset(toc.k)
                if (kOff + 8 > data.size) continue
                val kBuf = ByteBuffer.wrap(data, kOff, 8).order(ByteOrder.LITTLE_ENDIAN)
                val oid = kBuf.getLong()
                if (oid <= targetOid) {
                    val vOff = node.fixedValueOffset(toc.v)
                    if (vOff < 0 || vOff + 8 > data.size) continue
                    val vBuf = ByteBuffer.wrap(data, vOff, 8).order(ByteOrder.LITTLE_ENDIAN)
                    childAddr = vBuf.getLong()
                }
            }
            return if (childAddr != null) omapLookupNode(childAddr, targetOid, targetXid, depth + 1) else null
        }
    }

    /**
     * Scan the filesystem B-Tree rooted at fsTreeRootAddr and collect all file entries.
     * Returns a list of (oid, inode, name, parentId, extents).
     */
    fun scanFsTree(fsTreeRootAddr: Long): List<FsTreeResult> {
        val inodes = mutableMapOf<Long, ApfsInodeRecord>()
        val dirEntries = mutableListOf<Triple<Long, String, Long>>() // (parentOid, name, fileOid)
        val extents = mutableMapOf<Long, MutableList<ApfsExtent>>() // fileOid -> extents

        collectFsRecords(fsTreeRootAddr, inodes, dirEntries, extents, 0)

        val results = mutableListOf<FsTreeResult>()
        for ((parentOid, name, fileOid) in dirEntries) {
            val inode = inodes[fileOid] ?: continue
            val fileExtents = extents[fileOid] ?: emptyList()
            results.add(FsTreeResult(fileOid, parentOid, name, inode, fileExtents))
        }
        return results
    }

    private fun collectFsRecords(
        blockAddr: Long,
        inodes: MutableMap<Long, ApfsInodeRecord>,
        dirEntries: MutableList<Triple<Long, String, Long>>,
        extents: MutableMap<Long, MutableList<ApfsExtent>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val data = readBlock(blockAddr) ?: return
        val node = ApfsBTreeNode.parse(data) ?: return

        if (!node.isLeaf) {
            // Internal node: recurse into children
            for (i in 0 until node.nkeys) {
                val toc = node.getVariableTocEntry(i)
                val vOff = node.keyOffset(toc.vOff)
                if (vOff + 8 > data.size) continue
                val vBuf = ByteBuffer.wrap(data, vOff, 8).order(ByteOrder.LITTLE_ENDIAN)
                val childAddr = vBuf.getLong()
                collectFsRecords(childAddr, inodes, dirEntries, extents, depth + 1)
            }
            return
        }

        // Leaf node: parse records
        for (i in 0 until node.nkeys) {
            try {
                val toc = node.getVariableTocEntry(i)
                val kStart = node.keyOffset(toc.kOff)
                val kLen = toc.kLen
                if (kStart < 0 || kStart + 8 > data.size || kLen < 8) continue

                val keyBuf = ByteBuffer.wrap(data, kStart, kLen).order(ByteOrder.LITTLE_ENDIAN)
                val idAndType = keyBuf.getLong()
                val recType = ((idAndType ushr 60) and 0xFL).toInt()
                val oid = idAndType and 0x0FFFFFFFFFFFFFFFL

                // Value offset: from end of block going backwards
                val vStart = data.size - toc.vOff
                val vLen = toc.vLen
                if (vStart < 0 || vStart + vLen > data.size) continue

                when (recType) {
                    ApfsConstants.APFS_TYPE_INODE -> {
                        val inode = ApfsInodeRecord.parse(data, vStart) ?: continue
                        inodes[oid] = inode
                    }
                    ApfsConstants.APFS_TYPE_DIR_REC -> {
                        if (kLen < 12) continue
                        val nameLenAndHash = ByteBuffer.wrap(data, kStart + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                        val nameLen = nameLenAndHash and 0x3FF
                        if (nameLen <= 0 || kStart + 12 + nameLen > data.size) continue
                        val nameRaw = ByteArray(nameLen)
                        System.arraycopy(data, kStart + 12, nameRaw, 0, nameLen)
                        val name = String(nameRaw, 0, if (nameLen > 0 && nameRaw[nameLen - 1] == 0.toByte()) nameLen - 1 else nameLen, Charsets.UTF_8)
                        if (vLen < 18) continue
                        val fileId = ByteBuffer.wrap(data, vStart, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        dirEntries.add(Triple(oid, name, fileId))
                    }
                    ApfsConstants.APFS_TYPE_FILE_EXTENT -> {
                        if (kLen < 16 || vLen < 24) continue
                        val logicalAddr = ByteBuffer.wrap(data, kStart + 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        val lenAndFlags = ByteBuffer.wrap(data, vStart, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        val length = lenAndFlags and 0x00FFFFFFFFFFFFFFL
                        val physBlockNum = ByteBuffer.wrap(data, vStart + 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        val cryptoId = ByteBuffer.wrap(data, vStart + 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        val extent = ApfsExtent(logicalAddr, physBlockNum, length, cryptoId)
                        extents.getOrPut(oid) { mutableListOf() }.add(extent)
                    }
                }
            } catch (e: Exception) {
                // Skip malformed entries
            }
        }
    }
}

data class FsTreeResult(
    val oid: Long,
    val parentOid: Long,
    val name: String,
    val inode: ApfsInodeRecord,
    val extents: List<ApfsExtent>
)
