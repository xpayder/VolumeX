package com.fatalpuppet.volumex.storage.disk

interface BlockDeviceReader {
    fun open(): Boolean
    fun close()
    fun isOpen(): Boolean
    fun readSector(lba: Long): ByteArray?
    fun sectorSize(): Int
    fun writeSector(lba: Long, data: ByteArray): Boolean = false
    fun writeSectors(startLba: Long, data: ByteArray): Boolean {
        val ss = sectorSize()
        var offset = 0
        var lba = startLba
        while (offset + ss <= data.size) {
            if (!writeSector(lba, data.copyOfRange(offset, offset + ss))) return false
            offset += ss
            lba++
        }
        return true
    }
    fun flushCache(): Boolean = true
}
