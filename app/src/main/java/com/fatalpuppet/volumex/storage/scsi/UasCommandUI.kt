package com.fatalpuppet.volumex.storage.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UasCommandIU {

    const val IU_ID_COMMAND = 0x01

    fun build(
        tag: Int,
        lun: Long,
        cdb: ByteArray
    ): ByteArray {

        require(tag in 0..0xFFFF)
        require(lun >= 0)
        require(cdb.size <= 16)

        /*
         * UAS Command IU
         *
         * Byte 0:
         *   IU ID = 0x01
         *
         * Byte 1:
         *   Reserved
         *
         * Bytes 2-3:
         *   TAG
         *
         * Bytes 4-7:
         *   Reserved
         *
         * Bytes 8-15:
         *   LUN
         *
         * Bytes 16-31:
         *   CDB
         */

        val buffer = ByteBuffer
            .allocate(32)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.put(IU_ID_COMMAND.toByte())
        buffer.put(0)

        buffer.putShort(tag.toShort())

        buffer.putInt(0)

        buffer.putLong(lun)

        buffer.put(cdb)

        repeat(16 - cdb.size) {
            buffer.put(0)
        }

        return buffer.array()
    }
}