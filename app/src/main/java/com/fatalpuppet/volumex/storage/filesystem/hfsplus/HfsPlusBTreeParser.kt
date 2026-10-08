package com.fatalpuppet.volumex.storage.filesystem.hfsplus

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HfsCatalogEntry(
    val parentId: Int,
    val name: String,
    val recordType: Short,
    val catalogId: Int,
    val isDirectory: Boolean,
    val fileSize: Long,
    val createDate: Int,
    val modifyDate: Int,
    val dataFork: HfsPlusForkData? = null
)

class HfsPlusBTreeParser(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val volumeHeader: HfsPlusVolumeHeader
) {
    companion object {
        private const val TAG = "VolumeX"
        private const val MAX_NODES = 50000
    }

    private var nodeSize: Int = 8192
    private var rootNode: Int = 0
    private var headerInitialized = false

    /** Read a raw HFS+ allocation block (blockSize bytes) from the partition. */
    fun readBlock(blockNum: Int): ByteArray? {
        val blockSize = volumeHeader.blockSize.toLong()
        val sectorSize = reader.sectorSize().toLong()
        val sectorsPerBlock = blockSize / sectorSize
        val lba = partitionStartLba + blockNum * sectorsPerBlock
        val result = ByteArray(blockSize.toInt())
        for (i in 0 until sectorsPerBlock) {
            val sector = reader.readSector(lba + i) ?: return null
            System.arraycopy(sector, 0, result, (i * sectorSize).toInt(), sectorSize.toInt())
        }
        return result
    }

    /** Read a B-tree node by its node number. */
    fun readNode(nodeNum: Int): ByteArray? {
        val catalogStartBlock = volumeHeader.catalogFile.extents.firstOrNull()?.startBlock ?: return null
        if (catalogStartBlock == 0) return null

        // Each B-tree node is nodeSize bytes. A single HFS+ block may contain multiple nodes
        // or a node may span multiple blocks if nodeSize > blockSize.
        val blockSize = volumeHeader.blockSize
        val nodesPerBlock = blockSize / nodeSize
        val blockIdx = if (nodesPerBlock > 0) nodeNum / nodesPerBlock else nodeNum
        val nodeOffsetInBlock = (nodeNum % maxOf(nodesPerBlock, 1)) * nodeSize

        // Walk the catalog fork extents to find the right block
        val absoluteBlock = catalogStartBlock + blockIdx
        val blockData = readBlock(absoluteBlock) ?: return null

        if (nodeOffsetInBlock + nodeSize > blockData.size) return null
        return blockData.copyOfRange(nodeOffsetInBlock, nodeOffsetInBlock + nodeSize)
    }

    /** Initialize the B-tree header. Must be called before scanning. */
    fun initialize(): Boolean {
        val node0Data = readNode(0) ?: return false
        val desc = HfsPlusBTreeNode.parseDescriptor(node0Data)
        if (!desc.isHeader) {
            Log.w(TAG, "Node 0 is not a header node (kind=${desc.kind})")
            return false
        }

        // Header record is at the first record offset (from offset table)
        val tempNode = HfsPlusBTreeNode(desc, node0Data, node0Data.size)
        val headerRecOffset = tempNode.recordOffset(0)
        if (headerRecOffset < 0) return false

        val hdr = HfsPlusBTreeNode.parseHeaderRec(node0Data, headerRecOffset)
        nodeSize = hdr.nodeSize.toInt().and(0xFFFF).coerceAtLeast(512)
        rootNode = hdr.rootNode
        headerInitialized = true

        Log.i(TAG, "HFS+ BTree: nodeSize=$nodeSize, rootNode=$rootNode, depth=${hdr.treeDepth}, leafRecords=${hdr.leafRecords}")
        return true
    }

    /** Collect all catalog entries (files and folders) from the catalog B-tree. */
    fun scanAllEntries(): List<HfsCatalogEntry> {
        if (!headerInitialized) {
            if (!initialize()) return emptyList()
        }

        // Walk all leaf nodes by traversing the linked list from firstLeafNode
        val node0Data = readNode(0) ?: return emptyList()
        val tempNode = HfsPlusBTreeNode(HfsPlusBTreeNode.parseDescriptor(node0Data), node0Data, nodeSize)
        val headerOff = tempNode.recordOffset(0)
        if (headerOff < 0) return emptyList()
        val hdr = HfsPlusBTreeNode.parseHeaderRec(node0Data, headerOff)

        val results = mutableListOf<HfsCatalogEntry>()
        var nodeNum = hdr.firstLeafNode
        var visited = 0

        while (nodeNum != 0 && visited < MAX_NODES) {
            val nodeData = readNode(nodeNum) ?: break
            val desc = HfsPlusBTreeNode.parseDescriptor(nodeData)
            if (!desc.isLeaf) break

            for (i in 0 until desc.numRecords) {
                try {
                    parseLeafRecord(nodeData, nodeNum, i, results)
                } catch (e: Exception) {
                    // Skip bad records
                }
            }

            nodeNum = desc.fLink
            visited++
        }

        Log.i(TAG, "HFS+ scanned $visited nodes, found ${results.size} catalog entries")
        return results
    }

    private fun parseLeafRecord(nodeData: ByteArray, nodeNum: Int, recordIndex: Int, results: MutableList<HfsCatalogEntry>) {
        val node = HfsPlusBTreeNode(HfsPlusBTreeNode.parseDescriptor(nodeData), nodeData, nodeSize)
        val recOff = node.recordOffset(recordIndex)
        if (recOff < 0 || recOff >= nodeData.size) return

        // Parse catalog key
        val key = HfsPlusBTreeNode.parseCatalogKey(nodeData, recOff) ?: return
        val keyLenBytes = 2 + 4 + 2 + key.nodeName.length * 2  // keyLength field + parentID + nameLen + chars
        val valOff = recOff + keyLenBytes

        if (valOff + 2 > nodeData.size) return
        val recType = ByteBuffer.wrap(nodeData, valOff, 2).order(ByteOrder.BIG_ENDIAN).getShort()

        when (recType) {
            HfsPlusConstants.HFS_PLUS_FOLDER_RECORD -> {
                if (valOff + 70 > nodeData.size) return
                val vBuf = ByteBuffer.wrap(nodeData, valOff, 70).order(ByteOrder.BIG_ENDIAN)
                vBuf.getShort() // type
                vBuf.getShort() // flags
                vBuf.getInt()   // valence
                val folderID = vBuf.getInt()
                val createDate = vBuf.getInt()
                val contentModDate = vBuf.getInt()
                results.add(HfsCatalogEntry(
                    parentId = key.parentID,
                    name = key.nodeName,
                    recordType = recType,
                    catalogId = folderID,
                    isDirectory = true,
                    fileSize = 0L,
                    createDate = createDate,
                    modifyDate = contentModDate
                ))
            }
            HfsPlusConstants.HFS_PLUS_FILE_RECORD -> {
                if (valOff + 248 > nodeData.size) return
                val vBuf = ByteBuffer.wrap(nodeData, valOff, 248).order(ByteOrder.BIG_ENDIAN)
                vBuf.getShort() // type
                vBuf.getShort() // flags
                vBuf.getInt()   // reserved1
                val fileID = vBuf.getInt()
                val createDate = vBuf.getInt()
                val contentModDate = vBuf.getInt()
                vBuf.getInt()   // attributeModDate
                vBuf.getInt()   // accessDate
                vBuf.getInt()   // backupDate
                // permissions: 16 bytes
                repeat(4) { vBuf.getInt() }
                // userInfo + finderInfo: 32 bytes
                repeat(8) { vBuf.getInt() }
                // textEncoding, reserved2
                vBuf.getInt(); vBuf.getInt()
                // dataFork HFSPlusForkData (80 bytes)
                val dataFork = parseForkDataFromBuf(vBuf)
                results.add(HfsCatalogEntry(
                    parentId = key.parentID,
                    name = key.nodeName,
                    recordType = recType,
                    catalogId = fileID,
                    isDirectory = false,
                    fileSize = dataFork.logicalSize,
                    createDate = createDate,
                    modifyDate = contentModDate,
                    dataFork = dataFork
                ))
            }
            // Thread records: skip (they don't represent real entries)
        }
    }

    private fun parseForkDataFromBuf(buf: ByteBuffer): HfsPlusForkData {
        val logicalSize = buf.getLong()
        val clumpSize = buf.getInt()
        val totalBlocks = buf.getInt()
        val extents = (0 until 8).map {
            HfsPlusExtentDescriptor(buf.getInt(), buf.getInt())
        }
        return HfsPlusForkData(logicalSize, clumpSize, totalBlocks, extents)
    }

    /** Read the full content of a file's data fork. */
    fun readFileFork(fork: HfsPlusForkData): ByteArray? {
        if (fork.logicalSize <= 0) return ByteArray(0)
        val size = fork.logicalSize.coerceAtMost(100 * 1024 * 1024L).toInt()
        val result = ByteArray(size)
        var written = 0

        for (ext in fork.extents) {
            if (ext.blockCount == 0) continue
            if (written >= size) break
            for (b in 0 until ext.blockCount) {
                if (written >= size) break
                val blockData = readBlock(ext.startBlock + b) ?: break
                val toCopy = minOf(volumeHeader.blockSize, size - written)
                System.arraycopy(blockData, 0, result, written, toCopy)
                written += toCopy
            }
        }

        return result.copyOf(written)
    }
}
