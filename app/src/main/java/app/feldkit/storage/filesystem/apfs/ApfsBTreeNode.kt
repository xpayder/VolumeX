package app.feldkit.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class KvOff(val k: Int, val v: Int)    // fixed-size TOC entry
data class KvLoc(val kOff: Int, val kLen: Int, val vOff: Int, val vLen: Int)  // variable-size

data class ApfsBTreeNode(
    val header: ApfsObjectHeader,
    val flags: Int,
    val level: Int,
    val nkeys: Int,
    val tableSpaceOff: Int,
    val tableSpaceLen: Int,
    val freeSpaceOff: Int,
    val freeSpaceLen: Int,
    val keyFreeOff: Int,
    val keyFreeLen: Int,
    val valFreeOff: Int,
    val valFreeLen: Int,
    val data: ByteArray
) {
    val isLeaf: Boolean get() = (flags and ApfsConstants.BTNODE_LEAF) != 0
    val isRoot: Boolean get() = (flags and ApfsConstants.BTNODE_ROOT) != 0
    val isFixedKv: Boolean get() = (flags and ApfsConstants.BTNODE_FIXED_KV_SIZE) != 0

    // Data area starts at offset 0x38
    private val dataAreaStart = 0x38

    // Key area start = dataAreaStart + tableSpaceLen
    val keyAreaStart: Int get() = dataAreaStart + tableSpaceOff + tableSpaceLen

    // TOC starts at dataAreaStart + tableSpaceOff (usually 0x38)
    val tocStart: Int get() = dataAreaStart + tableSpaceOff

    fun getFixedTocEntry(index: Int): KvOff {
        val off = tocStart + index * 4
        val buf = ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN)
        val k = buf.getShort().toInt().and(0xFFFF)
        val v = buf.getShort().toInt().and(0xFFFF)
        return KvOff(k, v)
    }

    fun getVariableTocEntry(index: Int): KvLoc {
        val off = tocStart + index * 8
        val buf = ByteBuffer.wrap(data, off, 8).order(ByteOrder.LITTLE_ENDIAN)
        val kOff = buf.getShort().toInt().and(0xFFFF)
        val kLen = buf.getShort().toInt().and(0xFFFF)
        val vOff = buf.getShort().toInt().and(0xFFFF)
        val vLen = buf.getShort().toInt().and(0xFFFF)
        return KvLoc(kOff, kLen, vOff, vLen)
    }

    /** Absolute offset in data[] for a key, given TOC key offset */
    fun keyOffset(tocKeyOff: Int): Int = keyAreaStart + tocKeyOff

    /** Absolute offset in data[] for a value in a fixed-kv leaf node.
     * Values are stored from the END of the block going backward.
     * For leaf nodes: value offset is from end of block.
     * For internal (non-leaf) nodes: similar but different end pointer.
     */
    fun valueOffset(tocValOff: Int): Int {
        // Values stored from end of node
        return data.size - tocValOff - 8  // each omap value is 16 bytes but referenced from end
    }

    /**
     * For a FIXED_KV_SIZE leaf node, value offset from end of block.
     * val_off in TOC is the offset FROM THE END going backwards.
     */
    fun fixedValueOffset(tocValOff: Int): Int = valueAreaEnd - tocValOff

    /** Value area ends at the block end, minus the 40-byte btree_info_t that root nodes carry. */
    val valueAreaEnd: Int get() = data.size - (if (isRoot) BTREE_INFO_SIZE else 0)

    /** Absolute offset of a value for a variable-size TOC entry. */
    fun variableValueOffset(tocValOff: Int): Int = valueAreaEnd - tocValOff

    companion object {
        const val BTREE_INFO_SIZE = 40
        fun parse(data: ByteArray): ApfsBTreeNode? {
            if (data.size < 0x40) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val header = ApfsObjectHeader.parse(data, 0)

            buf.position(0x20)
            val flags = buf.getShort().toInt().and(0xFFFF)
            val level = buf.getShort().toInt().and(0xFFFF)
            val nkeys = buf.getInt()
            val tableSpaceOff = buf.getShort().toInt().and(0xFFFF)
            val tableSpaceLen = buf.getShort().toInt().and(0xFFFF)
            val freeSpaceOff = buf.getShort().toInt().and(0xFFFF)
            val freeSpaceLen = buf.getShort().toInt().and(0xFFFF)
            val keyFreeOff = buf.getShort().toInt().and(0xFFFF)
            val keyFreeLen = buf.getShort().toInt().and(0xFFFF)
            val valFreeOff = buf.getShort().toInt().and(0xFFFF)
            val valFreeLen = buf.getShort().toInt().and(0xFFFF)

            return ApfsBTreeNode(
                header, flags, level, nkeys,
                tableSpaceOff, tableSpaceLen,
                freeSpaceOff, freeSpaceLen,
                keyFreeOff, keyFreeLen,
                valFreeOff, valFreeLen,
                data
            )
        }
    }
}
