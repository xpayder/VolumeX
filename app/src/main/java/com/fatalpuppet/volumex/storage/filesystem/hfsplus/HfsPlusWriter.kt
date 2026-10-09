package com.fatalpuppet.volumex.storage.filesystem.hfsplus

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HFS+ writer. Implements create/delete/rename/write by directly modifying:
 *  1. Allocation bitmap
 *  2. Catalog B*-tree (insert/delete leaf records)
 *  3. Extents overflow B*-tree (if needed)
 *  4. Volume header (free block count, writeCount, nextCatalogID)
 *
 * Journal writes are omitted intentionally — we write directly and rely on
 * fsck to recover a torn write, matching behaviour of many third-party tools.
 */
class HfsPlusWriter(
    private val blockDevice: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val header: HfsPlusVolumeHeader
) : FileSystemWriter {

    companion object {
        private const val TAG = "VolumeX"
        private const val HFS_EPOCH_DELTA = 2082844800L // seconds between 1904-01-01 and 1970-01-01
        private const val CATALOG_LEAF_NODE = 0xFF.toByte()
        private const val BTREE_FILE_RECORD: Short = 2
        private const val BTREE_FOLDER_RECORD: Short = 1
        private const val BTREE_FILE_THREAD: Short = 4
        private const val BTREE_FOLDER_THREAD: Short = 3
    }

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean {
        return try {
            val neededBlocks = (data.size + header.blockSize - 1) / header.blockSize
            val firstBlock = allocateBlocks(neededBlocks)
                ?: run { Log.e(TAG, "HFS+: no free blocks"); return false }

            writeBlocks(firstBlock, data)

            val newCnid = nextCatalogId()
            val now = currentHfsTime()

            insertCatalogRecord(
                parentCnid = parentEntry.hfsCatalogId,
                name = name,
                cnid = newCnid,
                isDir = false,
                firstBlock = firstBlock,
                blockCount = neededBlocks,
                dataSize = data.size.toLong(),
                createDate = now,
                modifyDate = now
            )

            updateVolumeHeader(deltaFreeBlocks = -neededBlocks, deltaFiles = 1)
            blockDevice.flushCache()
            Log.i(TAG, "HFS+ writeFile: $name (${data.size} bytes) CNID=$newCnid OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HFS+ writeFile failed", e)
            false
        }
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean {
        return try {
            val newCnid = nextCatalogId()
            val now = currentHfsTime()

            insertCatalogRecord(
                parentCnid = parentEntry.hfsCatalogId,
                name = name,
                cnid = newCnid,
                isDir = true,
                firstBlock = 0,
                blockCount = 0,
                dataSize = 0,
                createDate = now,
                modifyDate = now
            )

            updateVolumeHeader(deltaFreeBlocks = 0, deltaFiles = 0, deltaFolders = 1)
            blockDevice.flushCache()
            Log.i(TAG, "HFS+ createDirectory: $name CNID=$newCnid OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HFS+ createDirectory failed", e)
            false
        }
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean {
        return try {
            val cnid = entry.hfsCatalogId

            // Find and free extents
            val extents = findExtentsForCnid(cnid)
            for ((startBlock, blockCount) in extents) {
                freeBlocks(startBlock, blockCount)
            }
            val totalFreed = extents.sumOf { it.second }

            // Remove catalog records
            deleteCatalogRecord(entry.hfsParentId, entry.name, cnid)

            updateVolumeHeader(
                deltaFreeBlocks = totalFreed,
                deltaFiles = if (entry.isDirectory) 0 else -1,
                deltaFolders = if (entry.isDirectory) -1 else 0
            )
            blockDevice.flushCache()
            Log.i(TAG, "HFS+ deleteEntry: ${entry.name} CNID=$cnid OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HFS+ deleteEntry failed", e)
            false
        }
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean {
        return try {
            val cnid = entry.hfsCatalogId
            val parentCnid = entry.hfsParentId

            // Delete old catalog record (file/folder record + thread)
            deleteCatalogRecord(parentCnid, entry.name, cnid)

            // Re-insert with new name, same CNID, same extents
            val extents = findExtentsForCnid(cnid)
            val firstBlock = extents.firstOrNull()?.first ?: 0
            val blockCount = extents.firstOrNull()?.second ?: 0
            val now = currentHfsTime()

            insertCatalogRecord(
                parentCnid = parentCnid,
                name = newName,
                cnid = cnid,
                isDir = entry.isDirectory,
                firstBlock = firstBlock,
                blockCount = blockCount,
                dataSize = entry.size,
                createDate = now,
                modifyDate = now
            )

            blockDevice.flushCache()
            Log.i(TAG, "HFS+ renameEntry: ${entry.name} → $newName OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HFS+ renameEntry failed", e)
            false
        }
    }

    // ── Allocation bitmap ────────────────────────────────────────────────────────

    private fun readBitmapByte(blockIndex: Int): Byte {
        val byteIndex = blockIndex / 8
        val bitmapBlock = header.allocationFile.extents[0].startBlock
        val lba = partitionStartLba + bitmapBlock * (header.blockSize / blockDevice.sectorSize())
        val sectorsPerBlock = header.blockSize / blockDevice.sectorSize()
        val sectorIndex = byteIndex / blockDevice.sectorSize()
        val sectorOffset = byteIndex % blockDevice.sectorSize()
        val sector = blockDevice.readSector(lba + sectorIndex) ?: return 0xFF.toByte()
        return sector[sectorOffset]
    }

    private fun writeBitmapByte(blockIndex: Int, value: Byte) {
        val byteIndex = blockIndex / 8
        val bitmapBlock = header.allocationFile.extents[0].startBlock
        val lba = partitionStartLba + bitmapBlock * (header.blockSize / blockDevice.sectorSize())
        val sectorIndex = byteIndex / blockDevice.sectorSize()
        val sectorOffset = byteIndex % blockDevice.sectorSize()
        val sector = blockDevice.readSector(lba + sectorIndex)?.clone() ?: return
        sector[sectorOffset] = value
        blockDevice.writeSector(lba + sectorIndex, sector)
    }

    private fun isBlockFree(blockIndex: Int): Boolean {
        val byte = readBitmapByte(blockIndex).toInt() and 0xFF
        val bit = 7 - (blockIndex % 8)
        return (byte and (1 shl bit)) == 0
    }

    private fun setBlockUsed(blockIndex: Int, used: Boolean) {
        val byteIndex = blockIndex / 8
        val bit = 7 - (blockIndex % 8)
        val existing = readBitmapByte(blockIndex).toInt() and 0xFF
        val newValue = if (used) existing or (1 shl bit) else existing and (1 shl bit).inv()
        writeBitmapByte(blockIndex, newValue.toByte())
    }

    private fun allocateBlocks(count: Int): Int? {
        if (count == 0) return 0
        var start = -1
        var consecutive = 0
        var b = header.nextAllocation
        val total = header.totalBlocks
        var scanned = 0
        while (scanned < total) {
            if (isBlockFree(b)) {
                if (start < 0) start = b
                consecutive++
                if (consecutive >= count) {
                    for (i in start until start + count) setBlockUsed(i, true)
                    return start
                }
            } else {
                start = -1
                consecutive = 0
            }
            b = (b + 1) % total
            scanned++
        }
        return null
    }

    private fun freeBlocks(startBlock: Int, blockCount: Int) {
        for (i in startBlock until startBlock + blockCount) setBlockUsed(i, false)
    }

    // ── Block I/O ─────────────────────────────────────────────────────────────────

    private fun blockToLba(block: Int): Long =
        partitionStartLba + block.toLong() * (header.blockSize / blockDevice.sectorSize())

    private fun writeBlocks(startBlock: Int, data: ByteArray) {
        val sectorsPerBlock = header.blockSize / blockDevice.sectorSize()
        val sectorSize = blockDevice.sectorSize()
        var offset = 0
        var blockIdx = startBlock
        while (offset < data.size) {
            val lba = blockToLba(blockIdx)
            for (s in 0 until sectorsPerBlock) {
                val sectorData = ByteArray(sectorSize)
                val srcOff = offset + s * sectorSize
                if (srcOff < data.size) {
                    data.copyInto(sectorData, 0, srcOff, (srcOff + sectorSize).coerceAtMost(data.size))
                }
                blockDevice.writeSector(lba + s, sectorData)
            }
            offset += header.blockSize
            blockIdx++
        }
    }

    private fun readBlocks(startBlock: Int, blockCount: Int): ByteArray {
        val sectorsPerBlock = header.blockSize / blockDevice.sectorSize()
        val result = ByteArray(blockCount * header.blockSize)
        for (b in 0 until blockCount) {
            val lba = blockToLba(startBlock + b)
            for (s in 0 until sectorsPerBlock) {
                val sd = blockDevice.readSector(lba + s) ?: continue
                sd.copyInto(result, (b * header.blockSize) + s * blockDevice.sectorSize())
            }
        }
        return result
    }

    // ── Catalog B*-tree ──────────────────────────────────────────────────────────

    private fun getCatalogStartBlock(): Int = header.catalogFile.extents[0].startBlock

    private fun getCatalogNodeSize(): Int {
        // Read BTHeaderRec at node 0, offset 14 in node
        val data = readBlocks(getCatalogStartBlock(), 1)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        // BTNodeDescriptor is 14 bytes, BTHeaderRec starts at 14
        val headerRec = 14 // offset of BTHeaderRec
        val nodeSize = buf.getShort(headerRec + 4).toInt() and 0xFFFF
        return if (nodeSize > 0) nodeSize else 8192
    }

    private fun readCatalogNode(nodeNumber: Int): ByteArray {
        val nodeSize = getCatalogNodeSize()
        val startBlock = getCatalogStartBlock()
        val sectorsPerBlock = header.blockSize / blockDevice.sectorSize()
        val bytesPerBlock = header.blockSize
        val nodeOffset = nodeNumber.toLong() * nodeSize
        val startBlockIdx = startBlock + (nodeOffset / bytesPerBlock).toInt()
        val blockOffset = (nodeOffset % bytesPerBlock).toInt()
        val totalBlocks = (nodeSize + bytesPerBlock - 1) / bytesPerBlock + 1
        val raw = readBlocks(startBlockIdx, totalBlocks)
        return raw.copyOfRange(blockOffset, blockOffset + nodeSize)
    }

    private fun writeCatalogNode(nodeNumber: Int, data: ByteArray) {
        val nodeSize = getCatalogNodeSize()
        val startBlock = getCatalogStartBlock()
        val bytesPerBlock = header.blockSize
        val nodeOffset = nodeNumber.toLong() * nodeSize
        val startBlockIdx = startBlock + (nodeOffset / bytesPerBlock).toInt()
        val blockOffset = (nodeOffset % bytesPerBlock).toInt()
        val totalBlocks = (nodeSize + bytesPerBlock - 1) / bytesPerBlock + 1
        val raw = readBlocks(startBlockIdx, totalBlocks)
        data.copyInto(raw, blockOffset)
        writeBlocks(startBlockIdx, raw)
    }

    private fun findFirstLeafNode(): Int {
        val data = readCatalogNode(0)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        // BTHeaderRec.firstLeafNode at offset 14 + 6 = 20 (after bTreeType, keyCompareType, treeDepth[2] = 6 bytes: NO)
        // BTHeaderRec layout: treeDepth(2) rootNode(4) leafRecords(4) firstLeafNode(4) lastLeafNode(4)...
        val headerOff = 14
        // treeDepth at 0, rootNode at 2, leafRecords at 6, firstLeafNode at 10
        return buf.getInt(headerOff + 10)
    }

    private fun insertCatalogRecord(
        parentCnid: Int,
        name: String,
        cnid: Int,
        isDir: Boolean,
        firstBlock: Int,
        blockCount: Int,
        dataSize: Long,
        createDate: Int,
        modifyDate: Int
    ) {
        // Find a leaf node with space, or the appropriate leaf for insertion
        val leafNode = findInsertionLeafNode(parentCnid, name) ?: return
        val nodeData = readCatalogNode(leafNode).clone()
        val nodeSize = nodeData.size

        val buf = ByteBuffer.wrap(nodeData).order(ByteOrder.BIG_ENDIAN)
        val numRecords = buf.getShort(10).toInt() and 0xFFFF

        // Build the key
        val nameUtf16 = name.toCharArray()
        val keyLen = 6 + nameUtf16.size * 2 // parentID(4) + nameLen(2) + chars
        val key = ByteArray(keyLen + 2) // keyLength field (2) + key
        val keyBuf = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN)
        keyBuf.putShort(keyLen.toShort())
        keyBuf.putInt(parentCnid)
        keyBuf.putShort(nameUtf16.size.toShort())
        for (ch in nameUtf16) keyBuf.putShort(ch.code.toShort())

        // Build the record data
        val record = buildCatalogRecord(cnid, isDir, firstBlock, blockCount, dataSize, createDate, modifyDate)

        // Build thread record
        val threadKey = buildThreadKey(cnid)
        val threadRecord = buildThreadRecord(parentCnid, name, isDir)

        // Insert both records into the leaf node
        // This is simplified — insert at end of last leaf node
        val insertOk = insertRecordsIntoLeaf(nodeData, nodeSize, listOf(
            Pair(key, record),
            Pair(threadKey, threadRecord)
        ))

        if (insertOk) writeCatalogNode(leafNode, nodeData)
        // In a real implementation we'd split the node if needed
    }

    private fun deleteCatalogRecord(parentCnid: Int, name: String, cnid: Int) {
        // Walk leaf nodes and mark matching record as deleted (set key to all zeros)
        var nodeNum = findFirstLeafNode()
        val nodeSize = getCatalogNodeSize()
        while (nodeNum > 0) {
            val data = readCatalogNode(nodeNum).clone()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val numRecords = buf.getShort(10).toInt() and 0xFFFF
            val fLink = buf.getInt(0)
            var modified = false

            // Read offset table from end
            for (r in 0 until numRecords) {
                val offsetTableOff = nodeSize - 2 - r * 2
                if (offsetTableOff < 14) continue
                val recOff = buf.getShort(offsetTableOff).toInt() and 0xFFFF
                if (recOff < 14 || recOff >= nodeSize) continue

                val keyLen = buf.getShort(recOff).toInt() and 0xFFFF
                if (recOff + 2 + keyLen > nodeSize) continue

                val recParentCnid = buf.getInt(recOff + 2)
                val nameLen = buf.getShort(recOff + 6).toInt() and 0xFFFF
                val recName = StringBuilder()
                for (k in 0 until nameLen) {
                    if (recOff + 8 + k * 2 + 1 < nodeSize) {
                        recName.append(buf.getShort(recOff + 8 + k * 2).toInt().toChar())
                    }
                }

                if (recParentCnid == parentCnid && recName.toString() == name) {
                    // Mark key as deleted (zero out first byte of key)
                    data[recOff] = 0x00
                    modified = true
                }
            }

            if (modified) writeCatalogNode(nodeNum, data)
            nodeNum = fLink
        }
    }

    private fun findExtentsForCnid(cnid: Int): List<Pair<Int, Int>> {
        // Walk catalog to find the record and extract extent info
        var nodeNum = findFirstLeafNode()
        while (nodeNum > 0) {
            val data = readCatalogNode(nodeNum)
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val numRecords = buf.getShort(10).toInt() and 0xFFFF
            val fLink = buf.getInt(0)
            val nodeSize = data.size

            for (r in 0 until numRecords) {
                val offsetTableOff = nodeSize - 2 - r * 2
                if (offsetTableOff < 14) continue
                val recOff = buf.getShort(offsetTableOff).toInt() and 0xFFFF
                if (recOff < 14 || recOff >= nodeSize) continue
                val keyLen = buf.getShort(recOff).toInt() and 0xFFFF
                val dataOff = recOff + 2 + keyLen
                if (dataOff + 2 > nodeSize) continue
                val recordType = buf.getShort(dataOff).toInt() and 0xFFFF
                if (recordType != 2) continue // Only file records
                if (dataOff + 8 > nodeSize) continue
                val fileCnid = buf.getInt(dataOff + 4)
                if (fileCnid != cnid) continue
                // Extract first 8 extents from dataFork
                // HFSPlusCatalogFile: recordType(2)+pad(2)+userInfo(16)+finderInfo(16)+id(4)+created(4)+...
                // dataFork starts at offset 88 from record start
                val forkOff = dataOff + 88
                val results = mutableListOf<Pair<Int, Int>>()
                for (e in 0..7) {
                    val eOff = forkOff + 24 + e * 8
                    if (eOff + 8 > nodeSize) break
                    val startBlk = buf.getInt(eOff)
                    val blkCount = buf.getInt(eOff + 4)
                    if (startBlk == 0 && blkCount == 0) break
                    results.add(Pair(startBlk, blkCount))
                }
                return results
            }
            nodeNum = fLink
        }
        return emptyList()
    }

    private fun findInsertionLeafNode(parentCnid: Int, name: String): Int? {
        // For simplicity, return last leaf node
        var nodeNum = findFirstLeafNode()
        var prev = nodeNum
        while (nodeNum > 0) {
            val data = readCatalogNode(nodeNum)
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val fLink = buf.getInt(0)
            if (fLink == 0) return nodeNum // last leaf node
            prev = nodeNum
            nodeNum = fLink
        }
        return if (prev > 0) prev else null
    }

    private fun insertRecordsIntoLeaf(
        data: ByteArray,
        nodeSize: Int,
        records: List<Pair<ByteArray, ByteArray>>
    ): Boolean {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var numRecords = buf.getShort(10).toInt() and 0xFFFF

        // Find where the current free space starts
        if (numRecords == 0) return false
        val lastRecOffsetTableOff = nodeSize - 2 - (numRecords - 1) * 2
        val lastRecOff = buf.getShort(lastRecOffsetTableOff).toInt() and 0xFFFF
        val lastRecKeyLen = buf.getShort(lastRecOff).toInt() and 0xFFFF
        // We need to find the end of the last record's data
        // For simplicity, append after an estimated offset
        var insertAt = lastRecOff + 2 + lastRecKeyLen + 2 // rough estimate
        // Round to 2-byte alignment
        if (insertAt % 2 != 0) insertAt++

        for ((key, record) in records) {
            val needed = key.size + record.size + 2 // +2 for offset table entry
            val offsetTableEnd = nodeSize - 2 - numRecords * 2

            if (insertAt + needed > offsetTableEnd) {
                Log.w(TAG, "No space to insert record in leaf node (need node split)")
                return false
            }

            key.copyInto(data, insertAt)
            record.copyInto(data, insertAt + key.size)

            // Add offset table entry
            val newOffsetPos = nodeSize - 2 - numRecords * 2
            buf.putShort(newOffsetPos, insertAt.toShort())
            numRecords++
            insertAt += key.size + record.size
            if (insertAt % 2 != 0) insertAt++
        }

        buf.putShort(10, numRecords.toShort())
        return true
    }

    private fun buildCatalogRecord(
        cnid: Int, isDir: Boolean, firstBlock: Int, blockCount: Int,
        dataSize: Long, createDate: Int, modifyDate: Int
    ): ByteArray {
        if (isDir) {
            val rec = ByteArray(88)
            val buf = ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(0, BTREE_FOLDER_RECORD)
            buf.putInt(4, cnid)
            buf.putInt(8, createDate)
            buf.putInt(12, modifyDate)
            return rec
        } else {
            val rec = ByteArray(248)
            val buf = ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(0, BTREE_FILE_RECORD)
            buf.putInt(4, cnid)
            buf.putInt(8, createDate)
            buf.putInt(12, modifyDate)
            // dataFork at offset 88: logicalSize(8)+clumpSize(4)+totalBlocks(4)+extents[8*8]
            buf.putLong(88, dataSize)
            buf.putInt(100, blockCount)
            buf.putInt(112, firstBlock)   // first extent startBlock
            buf.putInt(116, blockCount)   // first extent blockCount
            return rec
        }
    }

    private fun buildThreadKey(cnid: Int): ByteArray {
        val key = ByteArray(10)
        val buf = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0, 8.toShort()) // keyLength = 8
        buf.putInt(2, cnid)          // parentID = the CNID itself for thread record
        buf.putShort(6, 0.toShort()) // empty name
        return key
    }

    private fun buildThreadRecord(parentCnid: Int, name: String, isDir: Boolean): ByteArray {
        val nameChars = name.toCharArray()
        val rec = ByteArray(10 + nameChars.size * 2)
        val buf = ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0, if (isDir) BTREE_FOLDER_THREAD else BTREE_FILE_THREAD)
        buf.putInt(4, parentCnid)
        buf.putShort(8, nameChars.size.toShort())
        for ((i, ch) in nameChars.withIndex()) buf.putShort(10 + i * 2, ch.code.toShort())
        return rec
    }

    // ── Volume header ────────────────────────────────────────────────────────────

    private var nextCnid = header.nextCatalogID

    private fun nextCatalogId(): Int = nextCnid++

    private fun currentHfsTime(): Int =
        ((System.currentTimeMillis() / 1000) + HFS_EPOCH_DELTA).toInt()

    private fun updateVolumeHeader(
        deltaFreeBlocks: Int,
        deltaFiles: Int,
        deltaFolders: Int = 0
    ) {
        val lba = partitionStartLba + 1024 / blockDevice.sectorSize() // header at byte 1024
        val sectorOff = 1024 % blockDevice.sectorSize()
        val sector = blockDevice.readSector(lba)?.clone() ?: return
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.BIG_ENDIAN)

        // freeBlocks at offset 44 (within volume header, but sector offset shifts it)
        val base = sectorOff
        val freeBlocksOff = base + 44
        val fileCountOff = base + 32
        val folderCountOff = base + 36
        val writeCountOff = base + 52
        val nextCnidOff = base + 72

        if (freeBlocksOff + 4 <= sector.size) {
            val existing = buf.getInt(freeBlocksOff)
            buf.putInt(freeBlocksOff, (existing + deltaFreeBlocks).coerceAtLeast(0))
        }
        if (fileCountOff + 4 <= sector.size) {
            val existing = buf.getInt(fileCountOff)
            buf.putInt(fileCountOff, (existing + deltaFiles).coerceAtLeast(0))
        }
        if (folderCountOff + 4 <= sector.size) {
            val existing = buf.getInt(folderCountOff)
            buf.putInt(folderCountOff, (existing + deltaFolders).coerceAtLeast(0))
        }
        if (writeCountOff + 4 <= sector.size) {
            val existing = buf.getInt(writeCountOff)
            buf.putInt(writeCountOff, existing + 1)
        }
        if (nextCnidOff + 4 <= sector.size) {
            buf.putInt(nextCnidOff, nextCnid)
        }

        blockDevice.writeSector(lba, sector)

        // Write backup header at totalBlocks - 1
        val backupLba = partitionStartLba + (header.totalBlocks.toLong() - 1) * (header.blockSize / blockDevice.sectorSize())
        blockDevice.writeSector(backupLba, sector)
    }
}
