package com.fatalpuppet.volumex.storage.scsi

object ScsiInquiry {
    fun command(): ByteArray {
        return byteArrayOf(
            ScsiOpcodes.INQUIRY,
            0,
            0,
            0,
            36,
            0
        )
    }
}