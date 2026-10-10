package app.feldkit.storage.filesystem.exfat

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ExFatBootSectorParser {

    fun parse(
        sector: ByteArray,
        partitionStartLba: Long
    ): ExFatBootSector? {

        if (sector.size < 512) {
            return null
        }

        val buffer =
            ByteBuffer
                .wrap(sector)
                .order(ByteOrder.LITTLE_ENDIAN)

        // exFAT filesystem name
        val filesystemName =
            String(
                sector,
                3,
                8,
                Charsets.US_ASCII
            )

        if (filesystemName != "EXFAT   ") {
            return null
        }

        val partitionOffset =
            buffer.getLong(64)

        val volumeLength =
            buffer.getLong(72)

        val fatOffset =
            buffer.getInt(80)
                .toLong() and 0xFFFFFFFFL

        val fatLength =
            buffer.getInt(84)
                .toLong() and 0xFFFFFFFFL

        val clusterHeapOffset =
            buffer.getInt(88)
                .toLong() and 0xFFFFFFFFL

        val clusterCount =
            buffer.getInt(92)
                .toLong() and 0xFFFFFFFFL

        val rootDirectoryCluster =
            buffer.getInt(96)
                .toLong() and 0xFFFFFFFFL

        val volumeSerialNumber =
            buffer.getInt(100)
                .toLong() and 0xFFFFFFFFL

        val bytesPerSectorShift =
            sector[108].toInt() and 0xFF

        val sectorsPerClusterShift =
            sector[109].toInt() and 0xFF

        val numberOfFats =
            sector[110].toInt() and 0xFF

        return ExFatBootSector(
            partitionOffset = partitionOffset,
            volumeLength = volumeLength,
            fatOffset = fatOffset,
            fatLength = fatLength,
            clusterHeapOffset = clusterHeapOffset,
            clusterCount = clusterCount,
            rootDirectoryCluster = rootDirectoryCluster,
            bytesPerSectorShift = bytesPerSectorShift,
            sectorsPerClusterShift = sectorsPerClusterShift,
            numberOfFats = numberOfFats,
            volumeSerialNumber = volumeSerialNumber,
            partitionStartLba = partitionStartLba
        )
    }
}