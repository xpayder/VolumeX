package app.feldkit.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ApfsDirEntry(
    val parentId: Long,
    val name: String,
    val fileId: Long,        // inode OID
    val dateAdded: Long,
    val flags: Int
) {
    companion object {
        /**
         * Parse a DIR_REC key from data[] starting at keyOffset.
         * After j_key_t (8 bytes): name_len_and_hash (uint32), then null-terminated UTF-8 name.
         * keyOffset points to the start of the j_key_t.
         */
        fun parseKey(data: ByteArray, keyOffset: Int, keyLen: Int): Pair<Long, String>? {
            if (data.size - keyOffset < 12) return null
            val buf = ByteBuffer.wrap(data, keyOffset, data.size - keyOffset).order(ByteOrder.LITTLE_ENDIAN)
            val idAndType = buf.getLong()
            val parentId = idAndType and 0x0FFFFFFFFFFFFFFFL
            val nameLenAndHash = buf.getInt()
            val nameLen = nameLenAndHash and 0x3FF
            if (nameLen <= 0 || data.size - keyOffset - 12 < nameLen) return null
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            // nameLen includes null terminator
            val name = String(nameBytes, 0, if (nameLen > 0 && nameBytes[nameLen - 1] == 0.toByte()) nameLen - 1 else nameLen, Charsets.UTF_8)
            return Pair(parentId, name)
        }

        /**
         * Parse a DIR_REC value from data[] at valOffset.
         * j_drec_val_t: file_id (uint64), date_added (uint64), flags (uint16)
         */
        fun parseValue(data: ByteArray, valOffset: Int): Triple<Long, Long, Int>? {
            if (data.size - valOffset < 18) return null
            val buf = ByteBuffer.wrap(data, valOffset, data.size - valOffset).order(ByteOrder.LITTLE_ENDIAN)
            val fileId = buf.getLong()
            val dateAdded = buf.getLong()
            val flags = buf.getShort().toInt().and(0xFFFF)
            return Triple(fileId, dateAdded, flags)
        }
    }
}
