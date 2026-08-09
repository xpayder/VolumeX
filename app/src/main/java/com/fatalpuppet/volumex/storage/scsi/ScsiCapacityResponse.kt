package com.fatalpuppet.volumex.storage.scsi

data class ScsiCapacityResponse(
    val lastLogicalBlockAddress: Long,
    val blockSize: Int
) {
    val blockCount: Long
        get() = lastLogicalBlockAddress + 1

    val capacityBytes: Long
        get() = blockCount * blockSize
}

object ScsiCapacityResponseParser {

    fun parse(data: ByteArray): ScsiCapacityResponse? {

        if (data.size < 8) {
            return null
        }

        val lastLogicalBlockAddress =
            ((data[0].toLong() and 0xFF) shl 24) or
                    ((data[1].toLong() and 0xFF) shl 16) or
                    ((data[2].toLong() and 0xFF) shl 8) or
                    (data[3].toLong() and 0xFF)

        val blockSize =
            ((data[4].toInt() and 0xFF) shl 24) or
                    ((data[5].toInt() and 0xFF) shl 16) or
                    ((data[6].toInt() and 0xFF) shl 8) or
                    (data[7].toInt() and 0xFF)

        return ScsiCapacityResponse(
            lastLogicalBlockAddress = lastLogicalBlockAddress,
            blockSize = blockSize
        )
    }
}