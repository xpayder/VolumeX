package app.feldkit.storage.scsi

object ScsiReadCapacity {

    fun command(): ByteArray {
        return byteArrayOf(
            ScsiOpcodes.READ_CAPACITY_10,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        )
    }
}