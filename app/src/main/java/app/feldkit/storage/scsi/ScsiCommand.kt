// file: app/src/main/java/app/feldkit/storage/scsi/ScsiCommand.kt
package app.feldkit.storage.scsi

object ScsiCommand {
    const val INQUIRY = 0x12
    const val READ_CAPACITY = 0x25
    const val READ10 = 0x28
    const val TEST_UNIT_READY = 0x00
    const val READ16 = 0x88

    const val REQUEST_SENSE = 0x03

    fun requestSense(allocationLength: Int = 18): ByteArray {
        return byteArrayOf(
            REQUEST_SENSE.toByte(),
            0x00, 0x00, 0x00, allocationLength.toByte(), 0x00
        )
    }

    // Add the factory methods here
    fun testUnitReady(): ByteArray {
        return byteArrayOf(
            TEST_UNIT_READY.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    fun inquiry(evpd: Boolean = false, pageCode: Int = 0): ByteArray {
        return byteArrayOf(
            INQUIRY.toByte(),
            if (evpd) 0x01 else 0x00,
            pageCode.toByte(),
            0x00,
            0xFF.toByte(),
            0x00
        )
    }

    fun readCapacity(): ByteArray {
        return byteArrayOf(
            READ_CAPACITY.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
            0x00, 0x00
        )
    }

    fun read10(lba: Long, blockCount: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = READ10.toByte()
        cdb[1] = 0x00
        cdb[2] = ((lba shr 24) and 0xFF).toByte()
        cdb[3] = ((lba shr 16) and 0xFF).toByte()
        cdb[4] = ((lba shr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[6] = 0x00
        cdb[7] = ((blockCount shr 8) and 0xFF).toByte()
        cdb[8] = (blockCount and 0xFF).toByte()
        cdb[9] = 0x00
        return cdb
    }

    fun read16(lba: Long, blockCount: Long): ByteArray {
        val cdb = ByteArray(16)
        cdb[0] = READ16.toByte()
        cdb[1] = 0x00
        for (i in 0..7) {
            cdb[2 + i] = ((lba shr (56 - (i * 8))) and 0xFF).toByte()
        }
        for (i in 0..3) {
            cdb[10 + i] = ((blockCount shr (24 - (i * 8))) and 0xFF).toByte()
        }
        cdb[14] = 0x00
        cdb[15] = 0x00
        return cdb
    }
}