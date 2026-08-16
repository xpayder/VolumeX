// file: app/src/main/java/com/fatalpuppet/volumex/storage/filesystem/apfs/ApfsContainerSuperblockParser.kt
package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object ApfsContainerSuperblockParser {

    private const val APFS_CONTAINER_SB_MAGIC = 0x4253464AL // "BSF4" in little endian

    fun parse(data: ByteArray): ApfsContainerSuperblock? {
        if (data.size < 512) return null

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Check magic number
        val magic = buffer.int
        // Fix: Compare Int with Int (convert magic constant to Int)
        if (magic != APFS_CONTAINER_SB_MAGIC.toInt()) {
            return null
        }

        // Skip some fields to get to the ones we need
        buffer.position(0x20) // Skip to block size field

        val blockSize = buffer.long
        val blockCount = buffer.long

        // Skip to UUID (at offset 0x50)
        buffer.position(0x50)
        val uuidMost = buffer.long
        val uuidLeast = buffer.long
        val containerUuid = UUID(uuidMost, uuidLeast).toString()

        // Skip to object IDs
        buffer.position(0x88)
        val nextObjectId = buffer.long
        val nextTransactionId = buffer.long

        // Skip to volume count (at offset 0xE0)
        buffer.position(0xE0)
        val volumeCount = buffer.int

        return ApfsContainerSuperblock(
            blockSize = blockSize,
            blockCount = blockCount,
            containerUuid = containerUuid,
            nextObjectId = nextObjectId,
            nextTransactionId = nextTransactionId,
            volumeCount = volumeCount
        )
    }
}