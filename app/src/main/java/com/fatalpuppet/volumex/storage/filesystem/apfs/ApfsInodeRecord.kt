package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ApfsInodeRecord(
    val parentId: Long,
    val privateId: Long,
    val createTime: Long,       // nanoseconds since epoch
    val modTime: Long,
    val changeTime: Long,
    val accessTime: Long,
    val internalFlags: Long,
    val nchildrenOrNlink: Int,
    val defaultProtectionClass: Int,
    val writeGenerationCounter: Int,
    val bsdFlags: Int,
    val owner: Int,
    val group: Int,
    val mode: Int,
    val uncompressedSize: Long
) {
    val isDirectory: Boolean get() = (mode and ApfsConstants.S_IFMT) == ApfsConstants.S_IFDIR
    val isRegularFile: Boolean get() = (mode and ApfsConstants.S_IFMT) == ApfsConstants.S_IFREG
    val isSymlink: Boolean get() = (mode and ApfsConstants.S_IFMT) == ApfsConstants.S_IFLNK

    val createTimeMs: Long get() = createTime / 1_000_000L
    val modTimeMs: Long get() = modTime / 1_000_000L

    companion object {
        /**
         * @param length number of value bytes available at [offset] (j_inode_val_t is 92 bytes
         * followed by xfields). The file size lives in the DSTREAM xfield (type 8), not in
         * the fixed-part uncompressed_size, which is 0 for ordinary files.
         */
        fun parse(data: ByteArray, offset: Int, length: Int = data.size - offset): ApfsInodeRecord? {
            if (length < 92 || offset + length > data.size) return null
            val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
            val parentId = buf.getLong()
            val privateId = buf.getLong()
            val createTime = buf.getLong()
            val modTime = buf.getLong()
            val changeTime = buf.getLong()
            val accessTime = buf.getLong()
            val internalFlags = buf.getLong()
            val nchildrenOrNlink = buf.getInt()
            val defaultProtectionClass = buf.getInt()
            val writeGenerationCounter = buf.getInt()
            val bsdFlags = buf.getInt()
            val owner = buf.getInt()
            val group = buf.getInt()
            val mode = buf.getShort().toInt().and(0xFFFF)
            buf.getShort() // pad1
            var uncompressedSize = buf.getLong()
            if (length > 96) {
                parseDstreamSize(data, offset + 92, length - 92)?.let { uncompressedSize = it }
            }
            return ApfsInodeRecord(
                parentId, privateId, createTime, modTime, changeTime, accessTime,
                internalFlags, nchildrenOrNlink, defaultProtectionClass,
                writeGenerationCounter, bsdFlags, owner, group, mode, uncompressedSize
            )
        }

        /** Walks the xfield blob (xf_blob_t + x_field_t[] + 8-byte aligned data) for INO_EXT_TYPE_DSTREAM. */
        private fun parseDstreamSize(data: ByteArray, start: Int, len: Int): Long? {
            if (len < 4) return null
            val numExts = (data[start].toInt() and 0xFF) or ((data[start + 1].toInt() and 0xFF) shl 8)
            var dataPos = start + 4 + numExts * 4
            for (i in 0 until numExts) {
                val h = start + 4 + i * 4
                if (h + 4 > start + len) return null
                val type = data[h].toInt() and 0xFF
                val size = (data[h + 2].toInt() and 0xFF) or ((data[h + 3].toInt() and 0xFF) shl 8)
                if (type == 8 && size >= 8 && dataPos + 8 <= data.size) {
                    return ByteBuffer.wrap(data, dataPos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                }
                dataPos += (size + 7) and 7.inv()
            }
            return null
        }
    }
}
