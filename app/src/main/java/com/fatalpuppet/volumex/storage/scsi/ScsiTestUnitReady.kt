package com.fatalpuppet.volumex.storage.scsi

object ScsiTestUnitReady {
    const val EXPECTED_LENGTH = 0
    fun command(): ByteArray {
        return byteArrayOf(
            ScsiOpcodes.TEST_UNIT_READY,
            0,
            0,
            0,
            0,
            0
        )
    }
}