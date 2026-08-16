package com.fatalpuppet.volumex.storage.filesystem.partition

data class GptPartition(
    val index: Int,
    val typeGuid: String,
    val partitionGuid: String,
    val startLba: Long,
    val endLba: Long,
    val attributes: Long,
    val name: String
)

data class GptHeader(
    val revision: Long,
    val headerSize: Long,
    val currentLba: Long,
    val backupLba: Long,
    val firstUsableLba: Long,
    val lastUsableLba: Long,
    val partitionEntryLba: Long,
    val partitionEntryCount: Long,
    val partitionEntrySize: Long
)

object GptPartitionTable {

    private const val GPT_SIGNATURE = "EFI PART"

    fun parseHeader(
        sector: ByteArray
    ): GptHeader? {

        if (sector.size < 92) {
            return null
        }

        val signature =
            String(
                sector.copyOfRange(0, 8),
                Charsets.US_ASCII
            )

        if (signature != GPT_SIGNATURE) {
            return null
        }

        return GptHeader(
            revision = readUInt32LE(sector, 8),
            headerSize = readUInt32LE(sector, 12),
            currentLba = readUInt64LE(sector, 24),
            backupLba = readUInt64LE(sector, 32),
            firstUsableLba = readUInt64LE(sector, 40),
            lastUsableLba = readUInt64LE(sector, 48),
            partitionEntryLba = readUInt64LE(sector, 72),
            partitionEntryCount = readUInt32LE(sector, 80),
            partitionEntrySize = readUInt32LE(sector, 84)
        )
    }

    fun parseEntries(
        data: ByteArray,
        header: GptHeader
    ): List<GptPartition> {

        val entrySize = header.partitionEntrySize.toInt()
        val entryCount = header.partitionEntryCount.toInt()

        if (entrySize <= 0 || entryCount <= 0) {
            return emptyList()
        }

        if (entrySize < 128) {
            return emptyList()
        }

        val partitions = mutableListOf<GptPartition>()

        for (index in 0 until entryCount) {

            val offset = index * entrySize

            if (offset + entrySize > data.size) {
                break
            }

            val entry = data.copyOfRange(
                offset,
                offset + entrySize
            )

            // A completely zeroed GPT entry means the entry is unused.
            if (entry.all { it == 0.toByte() }) {
                continue
            }

            val typeGuid =
                formatGuid(
                    entry.copyOfRange(0, 16)
                )

            val partitionGuid =
                formatGuid(
                    entry.copyOfRange(16, 32)
                )

            val startLba =
                readUInt64LE(
                    entry,
                    32
                )

            val endLba =
                readUInt64LE(
                    entry,
                    40
                )

            val attributes =
                readUInt64LE(
                    entry,
                    48
                )

            val name =
                decodeName(
                    entry,
                    56,
                    72
                )

            partitions.add(
                GptPartition(
                    index = index + 1,
                    typeGuid = typeGuid,
                    partitionGuid = partitionGuid,
                    startLba = startLba,
                    endLba = endLba,
                    attributes = attributes,
                    name = name
                )
            )
        }

        return partitions
    }

    private fun readUInt32LE(
        data: ByteArray,
        offset: Int
    ): Long {

        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt64LE(
        data: ByteArray,
        offset: Int
    ): Long {

        var value = 0L

        for (i in 0 until 8) {

            value = value or
                    (
                            (data[offset + i].toLong() and 0xFF)
                                    shl (8 * i)
                            )
        }

        return value
    }

    private fun formatGuid(
        data: ByteArray
    ): String {

        if (data.size != 16) {
            return ""
        }

        val part1 =
            ((data[3].toInt() and 0xFF) shl 24) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[0].toInt() and 0xFF)

        val part2 =
            ((data[5].toInt() and 0xFF) shl 8) or
                    (data[4].toInt() and 0xFF)

        val part3 =
            ((data[7].toInt() and 0xFF) shl 8) or
                    (data[6].toInt() and 0xFF)

        val part4 =
            data[8].toInt() and 0xFF

        val part5 =
            data[9].toInt() and 0xFF

        val remaining =
            data
                .drop(10)
                .joinToString("") {
                    "%02x".format(it.toInt() and 0xFF)
                }

        return "%08x-%04x-%04x-%02x%02x-%s".format(
            part1,
            part2,
            part3,
            part4,
            part5,
            remaining
        )
    }

    private fun decodeName(
        data: ByteArray,
        offset: Int,
        length: Int
    ): String {

        return String(
            data,
            offset,
            length,
            Charsets.UTF_16LE
        ).trimEnd('\u0000')
    }
}