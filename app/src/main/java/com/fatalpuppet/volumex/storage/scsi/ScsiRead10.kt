package com.fatalpuppet.volumex.storage.scsi

object ScsiRead10 {

    fun command(
        lba: Long,
        transferLength: Int
    ): ByteArray {

        require(lba in 0..0xFFFFFFFFL)
        require(transferLength in 1..0xFFFF)

        return byteArrayOf(
            ScsiOpcodes.READ_10,

            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),

            0,

            ((transferLength shr 8) and 0xFF).toByte(),
            (transferLength and 0xFF).toByte(),

            0,
            0
        )
    }
}