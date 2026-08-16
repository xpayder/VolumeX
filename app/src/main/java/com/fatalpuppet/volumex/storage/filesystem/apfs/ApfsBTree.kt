// file: app/src/main/java/com/fatalpuppet/volumex/storage/filesystem/apfs/ApfsBTree.kt
package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApfsBTree(
    private val reader: BlockDeviceReader,
    private val containerStartLba: Long,
    private val blockSize: Long,
    private val rootObjectId: Long
) {
    companion object {
        private const val TAG = "VolumeX"
        private const val APFS_BTREE_NODE_MAGIC = 0x45525442L // "BTRE" in little endian
    }

    fun walkTree(): List<ApfsFileEntry> {
        val entries = mutableListOf<ApfsFileEntry>()

        try {
            // Start at root node
            val rootNodeLba = containerStartLba + (rootObjectId * blockSize / 512)
            Log.i(TAG, "Starting B-Tree walk at LBA $rootNodeLba")

            readNode(rootNodeLba, entries, depth = 0)

        } catch (e: Exception) {
            Log.e(TAG, "Error walking B-Tree", e)
        }

        return entries
    }

    // In ApfsBTree.kt, add this debug version
    private fun readNode(lba: Long, entries: MutableList<ApfsFileEntry>, depth: Int) {
        val sectorData = reader.readSector(lba) ?: return

        val buffer = ByteBuffer.wrap(sectorData)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Check magic
        val magic = buffer.long
        if (magic != APFS_BTREE_NODE_MAGIC) {
            Log.w(TAG, "Not a B-Tree node at LBA $lba, magic: ${String.format("0x%08X", magic)}")
            return
        }

        Log.i(TAG, "Found B-Tree node at LBA $lba (depth $depth)")

        // Parse node header
        buffer.position(0x10)
        val nodeType = buffer.short.toInt()
        val isLeaf = nodeType == 0x1000
        val keyCount = buffer.short.toInt()
        val nodeSize = buffer.int.toLong()

        Log.i(TAG, "Node: type=${if (isLeaf) "LEAF" else "INTERNAL"}, keys=$keyCount, size=$nodeSize")

        // For now, just show a summary
        if (keyCount > 0) {
            // Add some dummy entries that show we found data
            for (i in 0 until minOf(keyCount, 5)) {
                entries.add(
                    ApfsFileEntry(
                        objectId = lba + i,
                        name = "[Node_$lba]_key_$i",
                        isDirectory = false,
                        fileSize = 1024L,
                        creationTime = System.currentTimeMillis(),
                        modificationTime = System.currentTimeMillis()
                    )
                )
            }
            Log.i(TAG, "Added ${minOf(keyCount, 5)} sample entries from node $lba")
        }
    }

    private fun parseLeafNode(buffer: ByteBuffer, keyCount: Int, entries: MutableList<ApfsFileEntry>) {
        // Simplified parsing - in a real implementation, you'd need to handle the B-Tree key-value structure properly
        // This is a placeholder that simulates finding some file entries
        for (i in 0 until minOf(keyCount, 10)) {
            // Simulate reading file entries
            entries.add(
                ApfsFileEntry(
                    objectId = i.toLong() + 0x1000,
                    name = "file_$i.txt",
                    isDirectory = i % 3 == 0,
                    fileSize = (i + 1) * 1024L,
                    creationTime = System.currentTimeMillis(),
                    modificationTime = System.currentTimeMillis()
                )
            )
        }
    }

    private fun parseInternalNode(buffer: ByteBuffer, keyCount: Int, entries: MutableList<ApfsFileEntry>, depth: Int) {
        // In a real implementation, you'd read child node pointers and recursively walk them
        // For now, we'll simulate a few child nodes
        for (i in 0 until minOf(keyCount, 3)) {
            val childLba = containerStartLba + ((i + 1) * 0x100) // Simulated child locations
            readNode(childLba, entries, depth)
        }
    }
}