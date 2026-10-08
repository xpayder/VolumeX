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
        fun parse(data: ByteArray, offset: Int): ApfsInodeRecord? {
            if (data.size - offset < 92) return null
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
            val uncompressedSize = buf.getLong()
            return ApfsInodeRecord(
                parentId, privateId, createTime, modTime, changeTime, accessTime,
                internalFlags, nchildrenOrNlink, defaultProtectionClass,
                writeGenerationCounter, bsdFlags, owner, group, mode, uncompressedSize
            )
        }
    }
}
