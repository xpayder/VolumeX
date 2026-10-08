package com.fatalpuppet.volumex.storage.filesystem.apfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ApfsExtent(
    val logicalAddr: Long,       // logical byte offset in file
    val physBlockNum: Long,      // physical block address
    val length: Long,            // length in bytes
    val cryptoId: Long
) {
    companion object {
        /**
         * Parse FILE_EXTENT key: j_key_t (8 bytes) + logical_addr (8 bytes)
         */
        fun parseKey(data: ByteArray, keyOffset: Int): Pair<Long, Long>? {
            if (data.size - keyOffset < 16) return null
            val buf = ByteBuffer.wrap(data, keyOffset, data.size - keyOffset).order(ByteOrder.LITTLE_ENDIAN)
            val idAndType = buf.getLong()
            val inodeId = idAndType and 0x0FFFFFFFFFFFFFFFL
            val logicalAddr = buf.getLong()
            return Pair(inodeId, logicalAddr)
        }

        /**
         * Parse FILE_EXTENT value: j_file_extent_val_t
         * len_and_flags (uint64): length in low 56 bits
         * phys_block_num (uint64)
         * crypto_id (uint64)
         */
        fun parseValue(data: ByteArray, valOffset: Int): Triple<Long, Long, Long>? {
            if (data.size - valOffset < 24) return null
            val buf = ByteBuffer.wrap(data, valOffset, data.size - valOffset).order(ByteOrder.LITTLE_ENDIAN)
            val lenAndFlags = buf.getLong()
            val length = lenAndFlags and 0x00FFFFFFFFFFFFFFL
            val physBlockNum = buf.getLong()
            val cryptoId = buf.getLong()
            return Triple(length, physBlockNum, cryptoId)
        }
    }
}
