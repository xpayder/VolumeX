// file: app/src/main/java/com/fatalpuppet/volumex/storage/filesystem/apfs/ApfsVolumeSuperblockParser.kt
package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object ApfsVolumeSuperblockParser {

    private const val APFS_VOLUME_SB_MAGIC = 0x3153464AL // "FSF1" in little endian

    fun parse(data: ByteArray): ApfsVolumeSuperblock? {
        if (data.size < 512) return null

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Check magic number
        val magic = buffer.int
        // Fix: Compare Int with Int
        if (magic != APFS_VOLUME_SB_MAGIC.toInt()) {
            return null
        }

        // Parse volume name (at offset 0x20, 256 bytes, UTF-8)
        buffer.position(0x20)
        val nameBytes = ByteArray(256)
        buffer.get(nameBytes)
        val nameEnd = nameBytes.indexOfFirst { it == 0.toByte() }
        val volumeName = String(nameBytes, 0, if (nameEnd > 0) nameEnd else nameBytes.size, StandardCharsets.UTF_8)

        // Parse volume block size (at offset 0x120)
        buffer.position(0x120)
        val volumeBlockSize = buffer.long

        // Parse object count (at offset 0x128)
        val objectCount = buffer.long

        // Parse root directory object ID (at offset 0x130)
        val rootDirectoryObjectId = buffer.long

        // Parse extent count (at offset 0x138)
        val extentCount = buffer.long

        return ApfsVolumeSuperblock(
            volumeName = volumeName,
            volumeBlockSize = volumeBlockSize,
            objectCount = objectCount,
            rootDirectoryObjectId = rootDirectoryObjectId,
            extentCount = extentCount
        )
    }
}