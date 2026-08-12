package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object ApfsContainerSuperblockParser {

    private const val APFS_MAGIC = 0x4253584E

    fun parse(
        block: ByteArray
    ): ApfsContainerSuperblock? {

        if (block.size < 512) {
            return null
        }

        val buffer =
            ByteBuffer
                .wrap(block)
                .order(ByteOrder.LITTLE_ENDIAN)

        val magic =
            buffer.getInt(32)

        if (magic != APFS_MAGIC) {
            return null
        }

        val blockSize =
            buffer.getLong(36)
                .let { it and 0xFFFFFFFFL }

        val blockCount =
            buffer.getLong(40)

        val nextObjectId =
            buffer.getLong(80)

        val nextTransactionId =
            buffer.getLong(88)

        val containerUuid =
            readUuid(
                block,
                72
            )

        val volumeCount =
            buffer.getInt(116)

        return ApfsContainerSuperblock(
            blockSize = blockSize,
            blockCount = blockCount,
            containerUuid = containerUuid,
            nextObjectId = nextObjectId,
            nextTransactionId = nextTransactionId,
            volumeCount = volumeCount
        )
    }

    private fun readUuid(
        data: ByteArray,
        offset: Int
    ): String {

        val bytes =
            data.copyOfRange(
                offset,
                offset + 16
            )

        val buffer =
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)

        val mostSignificantBits =
            buffer.long

        val leastSignificantBits =
            buffer.long

        return UUID(
            mostSignificantBits,
            leastSignificantBits
        ).toString()
    }
}