package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * obj_phys_t: 32-byte object header present at the start of every APFS object.
 * Offset 0x00: checksum (8 bytes)
 * Offset 0x08: oid (8 bytes)
 * Offset 0x10: xid (8 bytes)
 * Offset 0x18: type (4 bytes)
 * Offset 0x1C: subtype (4 bytes)
 */
data class ApfsObjectHeader(
    val checksum: Long,
    val oid: Long,
    val xid: Long,
    val type: Int,
    val subtype: Int
) {
    val objectType: Int get() = type and ApfsConstants.OBJECT_TYPE_MASK
    val objectFlags: Int get() = (type.toLong() and 0xFFFF0000L).toInt()

    companion object {
        const val SIZE = 32

        fun parse(data: ByteArray, offset: Int = 0): ApfsObjectHeader {
            val buf = ByteBuffer.wrap(data, offset, data.size - offset)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val checksum = buf.getLong()
            val oid = buf.getLong()
            val xid = buf.getLong()
            val type = buf.getInt()
            val subtype = buf.getInt()
            return ApfsObjectHeader(checksum, oid, xid, type, subtype)
        }
    }
}
