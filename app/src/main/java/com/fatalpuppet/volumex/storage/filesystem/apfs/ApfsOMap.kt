package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ApfsOMap(
    val header: ApfsObjectHeader,
    val flags: Int,
    val snapCount: Int,
    val treeType: Int,
    val snapshotTreeType: Int,
    val treeOid: Long,       // OID of the B-Tree containing the map
    val snapshotTreeOid: Long
)

data class OMapKey(
    val oid: Long,
    val xid: Long
)

data class OMapValue(
    val flags: Int,
    val size: Int,
    val paddr: Long    // physical block address
)

object ApfsOMapParser {
    fun parse(data: ByteArray): ApfsOMap? {
        if (data.size < 0x40) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val header = ApfsObjectHeader.parse(data, 0)
        buf.position(0x20)
        val flags = buf.getInt()
        val snapCount = buf.getInt()
        val treeType = buf.getInt()
        val snapshotTreeType = buf.getInt()
        val treeOid = buf.getLong()
        val snapshotTreeOid = buf.getLong()
        return ApfsOMap(header, flags, snapCount, treeType, snapshotTreeType, treeOid, snapshotTreeOid)
    }

    fun parseOMapValue(data: ByteArray, offset: Int): OMapValue {
        val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.getInt()
        val size = buf.getInt()
        val paddr = buf.getLong()
        return OMapValue(flags, size, paddr)
    }
}
