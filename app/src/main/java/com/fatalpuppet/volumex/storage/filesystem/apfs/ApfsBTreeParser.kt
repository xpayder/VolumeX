package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses APFS B-trees.
 *
 * OMAP trees (physical, fixed K/V: key = omap_key_t{oid,xid}, value = omap_val_t{flags,size,paddr}).
 * FS trees (virtual, variable K/V): INODE, DIR_REC and FILE_EXTENT records. Child pointers in
 * FS-tree interior nodes are *virtual* OIDs that must be resolved through the volume's OMAP.
 */
class ApfsBTreeParser(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val blockSize: Long
) {
    companion object {
        private const val TAG = "VolumeX"
        private const val MAX_DEPTH = 16
    }

    private val sectorsPerBlock: Long get() = blockSize / reader.sectorSize()

    /** Read one APFS block (blockSize bytes) at physical block address [blockAddr]. */
    fun readBlock(blockAddr: Long): ByteArray? {
        if (blockAddr < 0) return null
        val lba = partitionStartLba + blockAddr * sectorsPerBlock
        return reader.readSectors(lba, sectorsPerBlock.toInt())
    }

    /**
     * Look up [targetOid] in an OMAP B-tree whose root is at physical block [omapTreeRootAddr].
     * Returns the physical address for the entry with the greatest xid <= [targetXid].
     */
    fun omapLookup(omapTreeRootAddr: Long, targetOid: Long, targetXid: Long = Long.MAX_VALUE): Long? =
        omapLookupNode(omapTreeRootAddr, targetOid, targetXid, 0)

    private fun omapLookupNode(blockAddr: Long, targetOid: Long, targetXid: Long, depth: Int): Long? {
        if (depth > MAX_DEPTH) return null
        val data = readBlock(blockAddr) ?: return null
        val node = ApfsBTreeNode.parse(data) ?: return null
        if (node.nkeys == 0) return null

        if (node.isLeaf) {
            var bestXid = -1L
            var bestPaddr: Long? = null
            for (i in 0 until node.nkeys) {
                val toc = node.getFixedTocEntry(i)
                val kOff = node.keyOffset(toc.k)
                if (kOff + 16 > data.size) continue
                val kBuf = ByteBuffer.wrap(data, kOff, 16).order(ByteOrder.LITTLE_ENDIAN)
                val oid = kBuf.getLong()
                val xid = kBuf.getLong()
                if (oid == targetOid && xid <= targetXid && xid > bestXid) {
                    val vOff = node.fixedValueOffset(toc.v)
                    if (vOff < 0 || vOff + 16 > data.size) continue
                    val vBuf = ByteBuffer.wrap(data, vOff, 16).order(ByteOrder.LITTLE_ENDIAN)
                    vBuf.getInt() // flags
                    vBuf.getInt() // size
                    bestXid = xid
                    bestPaddr = vBuf.getLong()
                }
            }
            return bestPaddr
        }

        // Interior node: child of the last key <= (targetOid, targetXid).
        var childAddr: Long? = null
        for (i in 0 until node.nkeys) {
            val toc = node.getFixedTocEntry(i)
            val kOff = node.keyOffset(toc.k)
            if (kOff + 16 > data.size) continue
            val kBuf = ByteBuffer.wrap(data, kOff, 16).order(ByteOrder.LITTLE_ENDIAN)
            val oid = kBuf.getLong()
            val xid = kBuf.getLong()
            if (oid < targetOid || (oid == targetOid && xid <= targetXid)) {
                val vOff = node.fixedValueOffset(toc.v)
                if (vOff < 0 || vOff + 8 > data.size) continue
                childAddr = ByteBuffer.wrap(data, vOff, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
            } else break
        }
        return childAddr?.let { omapLookupNode(it, targetOid, targetXid, depth + 1) }
    }

    /**
     * Scan the whole filesystem tree whose (virtual) root is [rootOid]. [resolve] maps a virtual
     * OID to its physical block (volume OMAP lookup).
     */
    fun scanFsTree(rootOid: Long, resolve: (Long) -> Long?): List<FsTreeResult> {
        val inodes = mutableMapOf<Long, ApfsInodeRecord>()
        val dirEntries = mutableListOf<DirRec>()
        val extents = mutableMapOf<Long, MutableList<ApfsExtent>>()
        collectFsRecords(rootOid, resolve, inodes, dirEntries, extents, 0)

        val results = mutableListOf<FsTreeResult>()
        for (d in dirEntries) {
            val inode = inodes[d.fileOid] ?: continue
            results.add(FsTreeResult(d.fileOid, d.parentOid, d.name, inode, extents[d.fileOid]?.sortedBy { it.logicalAddr } ?: emptyList()))
        }
        return results
    }

    private class DirRec(val parentOid: Long, val name: String, val fileOid: Long, val type: Int)

    private fun collectFsRecords(
        nodeOid: Long,
        resolve: (Long) -> Long?,
        inodes: MutableMap<Long, ApfsInodeRecord>,
        dirEntries: MutableList<DirRec>,
        extents: MutableMap<Long, MutableList<ApfsExtent>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val paddr = resolve(nodeOid) ?: run { Log.w(TAG, "APFS: cannot resolve fs-tree node oid=$nodeOid"); return }
        val data = readBlock(paddr) ?: return
        val node = ApfsBTreeNode.parse(data) ?: return

        if (!node.isLeaf) {
            for (i in 0 until node.nkeys) {
                val toc = node.getVariableTocEntry(i)
                val vOff = node.variableValueOffset(toc.vOff)
                if (vOff < 0 || vOff + 8 > data.size) continue
                val childOid = ByteBuffer.wrap(data, vOff, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                if (childOid != 0L) collectFsRecords(childOid, resolve, inodes, dirEntries, extents, depth + 1)
            }
            return
        }

        for (i in 0 until node.nkeys) {
            try {
                val toc = node.getVariableTocEntry(i)
                val kStart = node.keyOffset(toc.kOff)
                val kLen = toc.kLen
                if (kStart < 0 || kStart + 8 > data.size || kLen < 8) continue
                val idAndType = ByteBuffer.wrap(data, kStart, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                val recType = ((idAndType ushr 60) and 0xFL).toInt()
                val oid = idAndType and 0x0FFFFFFFFFFFFFFFL
                val vStart = node.variableValueOffset(toc.vOff)
                val vLen = toc.vLen
                if (vStart < 0 || vStart + vLen > data.size) continue

                when (recType) {
                    ApfsConstants.APFS_TYPE_INODE -> ApfsInodeRecord.parse(data, vStart, vLen)?.let { inodes[oid] = it }
                    ApfsConstants.APFS_TYPE_DIR_REC -> {
                        if (kLen < 12 || vLen < 18) continue
                        // j_drec_hashed_key_t: name_len_and_hash (u32: low 10 bits = length incl. NUL), then name.
                        val nameLen = ByteBuffer.wrap(data, kStart + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() and 0x3FF
                        if (nameLen <= 0 || kStart + 12 + nameLen > data.size) continue
                        val end = if (data[kStart + 12 + nameLen - 1] == 0.toByte()) nameLen - 1 else nameLen
                        val name = String(data, kStart + 12, end, Charsets.UTF_8)
                        val vb = ByteBuffer.wrap(data, vStart, vLen).order(ByteOrder.LITTLE_ENDIAN)
                        val fileId = vb.getLong()
                        vb.getLong() // date_added
                        val flags = vb.getShort().toInt() and 0xFFFF
                        dirEntries.add(DirRec(oid, name, fileId, flags and 0xF))
                    }
                    ApfsConstants.APFS_TYPE_FILE_EXTENT -> {
                        if (kLen < 16 || vLen < 24) continue
                        val logicalAddr = ByteBuffer.wrap(data, kStart + 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                        val vb = ByteBuffer.wrap(data, vStart, 24).order(ByteOrder.LITTLE_ENDIAN)
                        val length = vb.getLong() and 0x00FFFFFFFFFFFFFFL
                        val physBlockNum = vb.getLong()
                        val cryptoId = vb.getLong()
                        extents.getOrPut(oid) { mutableListOf() }.add(ApfsExtent(logicalAddr, physBlockNum, length, cryptoId))
                    }
                }
            } catch (e: Exception) {
                // skip malformed record
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
