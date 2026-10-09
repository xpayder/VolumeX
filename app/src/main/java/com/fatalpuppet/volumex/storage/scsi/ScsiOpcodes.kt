package com.fatalpuppet.volumex.storage.scsi

object ScsiOpcodes {

    const val TEST_UNIT_READY: Byte = 0x00

    const val REQUEST_SENSE: Byte = 0x03

    const val INQUIRY: Byte = 0x12

    const val READ_CAPACITY_10: Byte = 0x25

    const val READ_10: Byte = 0x28

    const val WRITE_10: Byte = 0x2A

    const val SYNCHRONIZE_CACHE: Byte = 0x35.toByte()

}