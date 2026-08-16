package com.fatalpuppet.volumex.storage.filesystem.partition

data class MbrPartitionEntry(
    val bootable: Boolean,
    val partitionType: Int,
    val startLba: Long,
    val sectorCount: Long
) {
    val endLba: Long
        get() = startLba + sectorCount - 1
}

object MbrPartitionTable {

    private const val PARTITION_TABLE_OFFSET = 446
    private const val PARTITION_ENTRY_SIZE = 16
    private const val PARTITION_COUNT = 4

    fun parse(sector: ByteArray): List<MbrPartitionEntry>? {

        if (sector.size < 512) {
            return null
        }

        if (
            sector[510].toInt() and 0xFF != 0x55 ||
            sector[511].toInt() and 0xFF != 0xAA
        ) {
            return null
        }

        val partitions = mutableListOf<MbrPartitionEntry>()

        for (index in 0 until PARTITION_COUNT) {

            val offset =
                PARTITION_TABLE_OFFSET +
                        index * PARTITION_ENTRY_SIZE

            val bootable =
                (sector[offset].toInt() and 0xFF) == 0x80

            val partitionType =
                sector[offset + 4].toInt() and 0xFF

            val startLba =
                readUInt32LittleEndian(
                    sector,
                    offset + 8
                )

            val sectorCount =
                readUInt32LittleEndian(
                    sector,
                    offset + 12
                )

            if (partitionType != 0 && sectorCount != 0L) {

                partitions.add(
                    MbrPartitionEntry(
                        bootable = bootable,
                        partitionType = partitionType,
                        startLba = startLba,
                        sectorCount = sectorCount
                    )
                )
            }
        }

        return partitions
    }

    private fun readUInt32LittleEndian(
        data: ByteArray,
        offset: Int
    ): Long {

        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}