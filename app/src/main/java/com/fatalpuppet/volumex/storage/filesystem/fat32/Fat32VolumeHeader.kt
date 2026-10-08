package com.fatalpuppet.volumex.storage.filesystem.fat32

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Fat32VolumeHeader(
    val oemName: String,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatCount: Int,
    val totalSectors32: Long,
    val fatSize32: Long,
    val rootCluster: Long,
    val volumeLabel: String,
    val fsType: String,
    // Derived values (set by parser)
    val partitionStartLba: Long,
    val fatStartLba: Long,
    val dataAreaLba: Long
) {
    val clusterSize: Int get() = bytesPerSector * sectorsPerCluster
}

object Fat32VolumeHeaderParser {
    fun parse(sector: ByteArray, partitionStartLba: Long): Fat32VolumeHeader? {
        if (sector.size < 512) return null
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)

        // Jump boot (3 bytes) — skip
        buf.position(3)
        val oemBytes = ByteArray(8)
        buf.get(oemBytes)
        val oemName = String(oemBytes).trim()

        val bytesPerSector = buf.getShort().toInt() and 0xFFFF
        if (bytesPerSector != 512 && bytesPerSector != 1024 && bytesPerSector != 2048 && bytesPerSector != 4096) return null

        val sectorsPerCluster = buf.get().toInt() and 0xFF
        if (sectorsPerCluster == 0) return null

        val reservedSectors = buf.getShort().toInt() and 0xFFFF
        val fatCount = buf.get().toInt() and 0xFF
        if (fatCount == 0) return null

        // Root entry count (bytes 17-18) — must be 0 for FAT32
        val rootEntryCount = buf.getShort().toInt() and 0xFFFF
        // Total sectors 16 (bytes 19-20) — must be 0 for FAT32
        val totalSectors16 = buf.getShort().toInt() and 0xFFFF
        buf.position(21) // skip media type
        buf.get() // media
        val fatSize16 = buf.getShort().toInt() and 0xFFFF // must be 0 for FAT32
        buf.position(32) // skip sectorsPerTrack, numHeads, hiddenSectors

        val totalSectors32 = buf.getInt().toLong() and 0xFFFFFFFFL

        // FAT32 extended BPB starts at offset 36
        buf.position(36)
        val fatSize32 = buf.getInt().toLong() and 0xFFFFFFFFL
        val extFlags = buf.getShort()
        val fsVersion = buf.getShort()
        val rootCluster = buf.getInt().toLong() and 0xFFFFFFFFL

        // Validate: FAT32 markers
        if (fatSize16 != 0 || rootEntryCount != 0) return null
        if (fatSize32 == 0L || rootCluster < 2) return null

        // Volume label at offset 71, FS type string at 82
        buf.position(71)
        val labelBytes = ByteArray(11)
        buf.get(labelBytes)
        val label = String(labelBytes).trim()

        buf.position(82)
        val fsTypeBytes = ByteArray(8)
        buf.get(fsTypeBytes)
        val fsType = String(fsTypeBytes).trim()

        if (!fsType.startsWith("FAT32") && !fsType.startsWith("FAT")) {
            // Also accept by checking signature byte at 510
            if (sector[510].toInt() and 0xFF != 0x55 || sector[511].toInt() and 0xFF != 0xAA) return null
        }

        val fatStartLba = partitionStartLba + reservedSectors
        val dataAreaLba = fatStartLba + fatCount * fatSize32

        return Fat32VolumeHeader(
            oemName = oemName,
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = reservedSectors,
            fatCount = fatCount,
            totalSectors32 = totalSectors32,
            fatSize32 = fatSize32,
            rootCluster = rootCluster,
            volumeLabel = label,
            fsType = fsType,
            partitionStartLba = partitionStartLba,
            fatStartLba = fatStartLba,
            dataAreaLba = dataAreaLba
        )
    }
}
