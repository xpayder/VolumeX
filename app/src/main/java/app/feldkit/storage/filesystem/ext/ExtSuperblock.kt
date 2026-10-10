package app.feldkit.storage.filesystem.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ExtSuperblock(
    val inodesCount: Long,
    val blocksCount: Long,
    val freeBlocks: Long,
    val freeInodes: Long,
    val firstDataBlock: Long,
    val logBlockSize: Int,
    val blocksPerGroup: Long,
    val inodesPerGroup: Long,
    val magic: Int,
    val inodeSize: Int,
    val featureIncompat: Int,
    val featureCompat: Int,
    val featureRoCompat: Int,
    val volumeName: String,
    val descSize: Int,        // Block group descriptor size (ext4 64-bit)
    // Derived
    val blockSize: Int,       // = 1024 << logBlockSize
    val groupDescSize: Int    // 32 for ext2/3, 64 for ext4 with 64-bit incompat
) {
    val hasExtents: Boolean get() = featureIncompat and ExtConstants.INCOMPAT_EXTENTS != 0
    val has64bit: Boolean get() = featureIncompat and ExtConstants.INCOMPAT_64BIT != 0
    val hasFlexBg: Boolean get() = featureIncompat and ExtConstants.INCOMPAT_FLEX_BG != 0
}

object ExtSuperblockParser {
    /**
     * Parse the ext2/3/4 superblock.
     * The superblock is always at byte offset 1024 from the partition start, regardless of block size.
     * For 512-byte sectors, that means sector 2, byte offset 0.
     */
    fun parse(data: ByteArray): ExtSuperblock? {
        if (data.size < 1024) return null
        val buf = ByteBuffer.wrap(data, 0, data.size).order(ByteOrder.LITTLE_ENDIAN)

        // Read fields
        val inodesCount = buf.getInt(ExtConstants.SB_INODES_COUNT).toLong() and 0xFFFFFFFFL
        val blocksCount = buf.getInt(ExtConstants.SB_BLOCKS_COUNT_LO).toLong() and 0xFFFFFFFFL
        val freeBlocks = buf.getInt(ExtConstants.SB_FREE_BLOCKS_LO).toLong() and 0xFFFFFFFFL
        val freeInodes = buf.getInt(ExtConstants.SB_FREE_INODES).toLong() and 0xFFFFFFFFL
        val firstDataBlock = buf.getInt(ExtConstants.SB_FIRST_DATA_BLOCK).toLong() and 0xFFFFFFFFL
        val logBlockSize = buf.getInt(ExtConstants.SB_LOG_BLOCK_SIZE)
        val blocksPerGroup = buf.getInt(ExtConstants.SB_BLOCKS_PER_GROUP).toLong() and 0xFFFFFFFFL
        val inodesPerGroup = buf.getInt(ExtConstants.SB_INODES_PER_GROUP).toLong() and 0xFFFFFFFFL
        val magic = buf.getShort(ExtConstants.SB_MAGIC).toInt() and 0xFFFF

        if (magic != ExtConstants.EXT_MAGIC) return null

        val inodeSize = if (data.size > ExtConstants.SB_INODE_SIZE + 1) {
            buf.getShort(ExtConstants.SB_INODE_SIZE).toInt() and 0xFFFF
        } else 128

        val featureCompat = if (data.size > ExtConstants.SB_FEATURE_COMPAT + 3) buf.getInt(ExtConstants.SB_FEATURE_COMPAT) else 0
        val featureIncompat = if (data.size > ExtConstants.SB_FEATURE_INCOMPAT + 3) buf.getInt(ExtConstants.SB_FEATURE_INCOMPAT) else 0
        val featureRoCompat = if (data.size > ExtConstants.SB_FEATURE_RO_COMPAT + 3) buf.getInt(ExtConstants.SB_FEATURE_RO_COMPAT) else 0

        val nameBytes = ByteArray(16)
        if (data.size > ExtConstants.SB_VOLUME_NAME + 15) {
            System.arraycopy(data, ExtConstants.SB_VOLUME_NAME, nameBytes, 0, 16)
        }
        val volumeName = String(nameBytes).trimEnd('\u0000')

        val descSize = if (data.size > ExtConstants.SB_DESC_SIZE + 1) {
            buf.getShort(ExtConstants.SB_DESC_SIZE).toInt() and 0xFFFF
        } else 0

        val blockSize = 1024 shl logBlockSize
        val has64bit = (featureIncompat and ExtConstants.INCOMPAT_64BIT) != 0
        val groupDescSize = if (has64bit && descSize >= 64) 64 else 32

        return ExtSuperblock(
            inodesCount = inodesCount,
            blocksCount = blocksCount,
            freeBlocks = freeBlocks,
            freeInodes = freeInodes,
            firstDataBlock = firstDataBlock,
            logBlockSize = logBlockSize,
            blocksPerGroup = blocksPerGroup,
            inodesPerGroup = inodesPerGroup,
            magic = magic,
            inodeSize = inodeSize.coerceAtLeast(128),
            featureIncompat = featureIncompat,
            featureCompat = featureCompat,
            featureRoCompat = featureRoCompat,
            volumeName = volumeName,
            descSize = descSize,
            blockSize = blockSize,
            groupDescSize = groupDescSize
        )
    }
}
