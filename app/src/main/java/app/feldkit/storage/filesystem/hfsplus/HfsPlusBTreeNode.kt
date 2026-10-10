package app.feldkit.storage.filesystem.hfsplus

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BTNodeDescriptor(
    val fLink: Int,
    val bLink: Int,
    val kind: Byte,
    val height: Byte,
    val numRecords: Int,
    val reserved: Short
) {
    val isLeaf: Boolean get() = kind == HfsPlusConstants.BT_LEAF_NODE
    val isIndex: Boolean get() = kind == HfsPlusConstants.BT_INDEX_NODE
    val isHeader: Boolean get() = kind == HfsPlusConstants.BT_HEADER_NODE
}

data class BTHeaderRec(
    val treeDepth: Short,
    val rootNode: Int,
    val leafRecords: Int,
    val firstLeafNode: Int,
    val lastLeafNode: Int,
    val nodeSize: Short,
    val maxKeyLength: Short,
    val totalNodes: Int,
    val freeNodes: Int,
    val reserved1: Short,
    val clumpSize: Int,
    val btreeType: Byte,
    val keyCompareType: Byte,
    val attributes: Int
)

data class HfsPlusCatalogKey(
    val keyLength: Short,
    val parentID: Int,
    val nodeName: String
)

data class HfsPlusBTreeNode(
    val descriptor: BTNodeDescriptor,
    val data: ByteArray,
    val nodeSize: Int
) {
    /** Get the offset of record at given index from the offset table at the end of node. */
    fun recordOffset(index: Int): Int {
        val tableOffset = nodeSize - (index + 1) * 2
        if (tableOffset < 0 || tableOffset + 2 > data.size) return -1
        val buf = ByteBuffer.wrap(data, tableOffset, 2).order(ByteOrder.BIG_ENDIAN)
        return buf.getShort().toInt().and(0xFFFF)
    }

    fun recordLength(index: Int): Int {
        val thisOff = recordOffset(index)
        val nextOff = recordOffset(index + 1)
        if (thisOff < 0 || nextOff < 0) return -1
        return nextOff - thisOff
    }

    companion object {
        const val DESCRIPTOR_SIZE = 14

        fun parseDescriptor(data: ByteArray, offset: Int = 0): BTNodeDescriptor {
            val buf = ByteBuffer.wrap(data, offset, 14).order(ByteOrder.BIG_ENDIAN)
            val fLink = buf.getInt()
            val bLink = buf.getInt()
            val kind = buf.get()
            val height = buf.get()
            val numRecords = buf.getShort().toInt().and(0xFFFF)
            val reserved = buf.getShort()
            return BTNodeDescriptor(fLink, bLink, kind, height, numRecords, reserved)
        }

        fun parseHeaderRec(data: ByteArray, offset: Int): BTHeaderRec {
            val buf = ByteBuffer.wrap(data, offset, 106).order(ByteOrder.BIG_ENDIAN)
            val treeDepth = buf.getShort()
            val rootNode = buf.getInt()
            val leafRecords = buf.getInt()
            val firstLeafNode = buf.getInt()
            val lastLeafNode = buf.getInt()
            val nodeSize = buf.getShort()
            val maxKeyLength = buf.getShort()
            val totalNodes = buf.getInt()
            val freeNodes = buf.getInt()
            val reserved1 = buf.getShort()
            val clumpSize = buf.getInt()
            val btreeType = buf.get()
            val keyCompareType = buf.get()
            val attributes = buf.getInt()
            return BTHeaderRec(treeDepth, rootNode, leafRecords, firstLeafNode, lastLeafNode,
                nodeSize, maxKeyLength, totalNodes, freeNodes, reserved1, clumpSize, btreeType, keyCompareType, attributes)
        }

        fun parseCatalogKey(data: ByteArray, offset: Int): HfsPlusCatalogKey? {
            if (data.size - offset < 6) return null
            val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.BIG_ENDIAN)
            val keyLength = buf.getShort()
            val parentID = buf.getInt()
            val nameLength = buf.getShort().toInt().and(0xFFFF)
            if (nameLength > 255 || data.size - offset - 8 < nameLength * 2) return null
            val nameChars = CharArray(nameLength) { buf.getShort().toInt().and(0xFFFF).toChar() }
            val name = java.text.Normalizer.normalize(String(nameChars), java.text.Normalizer.Form.NFC)
            return HfsPlusCatalogKey(keyLength, parentID, name)
        }
    }
}
