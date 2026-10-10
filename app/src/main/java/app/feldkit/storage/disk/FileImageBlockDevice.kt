package app.feldkit.storage.disk

import java.io.File
import java.io.RandomAccessFile

/**
 * A raw disk image file presented as a 512-byte-sector block device. Used by debug builds to run the
 * app against virtual disks (APFS / HFS+ / exFAT / FAT32 images) instead of a physical USB drive.
 */
class FileImageBlockDevice(private val file: File) : BlockDeviceReader {
    private var raf: RandomAccessFile? = null
    override fun open(): Boolean { raf = RandomAccessFile(file, "rw"); return true }
    override fun close() { raf?.close(); raf = null }
    override fun isOpen() = raf != null
    override fun sectorSize() = 512
    override fun sectorCount() = file.length() / 512

    @Synchronized override fun readSector(lba: Long): ByteArray? = readSectors(lba, 1)

    @Synchronized override fun readSectors(startLba: Long, count: Int): ByteArray? {
        val r = raf ?: return null
        if (startLba < 0 || (startLba + count) * 512 > file.length()) return null
        val b = ByteArray(count * 512); r.seek(startLba * 512); r.readFully(b); return b
    }

    @Synchronized override fun writeSector(lba: Long, data: ByteArray): Boolean = writeSectors(lba, data)

    @Synchronized override fun writeSectors(startLba: Long, data: ByteArray): Boolean {
        val r = raf ?: return false
        if (startLba < 0 || data.size % 512 != 0 || startLba * 512 + data.size > file.length()) return false
        r.seek(startLba * 512); r.write(data); return true
    }

    override fun flushCache(): Boolean { raf?.fd?.sync(); return true }
}
