package app.feldkit.storage.disk

interface BlockDeviceReader {
    fun open(): Boolean
    fun close()
    fun isOpen(): Boolean
    fun readSector(lba: Long): ByteArray?
    fun sectorSize(): Int
    fun sectorCount(): Long = 0L
    /** Read [count] consecutive sectors in one call. Implementations should override with a bulk transfer. */
    fun readSectors(startLba: Long, count: Int): ByteArray? {
        val ss = sectorSize()
        val out = ByteArray(count * ss)
        for (i in 0 until count) {
            val s = readSector(startLba + i) ?: return null
            System.arraycopy(s, 0, out, i * ss, ss)
        }
        return out
    }
    /** Forget cached reads, so the next read really comes from the media (used when verifying a write). */
    fun dropReadCache() {}
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
