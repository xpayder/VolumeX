package app.feldkit.storage.disk

import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader

/** Random-access byte source; lets disk-image parsers read a file on a mounted drive, or a plain file in tests. */
interface ByteSource {
    val length: Long
    /** Reads up to [len] bytes at [offset] into [buf]; returns the count read (0 at the end, -1 on error). */
    fun read(offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int
}

class ReaderByteSource(private val reader: FileSystemReader, private val entry: FileSystemEntry) : ByteSource {
    override val length: Long get() = entry.size
    override fun read(offset: Long, buf: ByteArray, bufOff: Int, len: Int) = reader.readRange(entry, offset, buf, bufOff, len)
}

class FileByteSource(private val file: java.io.File) : ByteSource {
    private val raf = java.io.RandomAccessFile(file, "r")
    override val length: Long get() = file.length()
    @Synchronized override fun read(offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        if (offset >= length) return 0
        raf.seek(offset); return raf.read(buf, bufOff, minOf(len.toLong(), length - offset).toInt())
    }
}

/** A raw disk image (ISO, IMG, ...) stored as a file, presented as a 512-byte-sector block device with a small read cache. */
open class SourceBlockDevice(private val src: ByteSource) : BlockDeviceReader {
    private val chunk = 1 shl 20
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>?) = size > 12 }

    override fun open() = true
    override fun close() {}
    override fun isOpen() = true
    override fun sectorSize() = 512
    override fun sectorCount() = src.length / 512

    @Synchronized private fun chunkAt(index: Long): ByteArray? {
        cache[index]?.let { return it }
        val off = index * chunk
        if (off >= src.length) return null
        val want = minOf(chunk.toLong(), src.length - off).toInt()
        val b = ByteArray(want); var got = 0
        while (got < want) { val n = src.read(off + got, b, got, want - got); if (n <= 0) return null; got += n }
        cache[index] = b
        return b
    }

    override fun readSector(lba: Long): ByteArray? = readSectors(lba, 1)

    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        if (startLba < 0 || (startLba + count) * 512 > src.length) return null
        val out = ByteArray(count * 512); var done = 0; var pos = startLba * 512
        while (done < out.size) {
            val c = chunkAt(pos / chunk) ?: return null
            val inChunk = (pos % chunk).toInt(); val n = minOf(out.size - done, c.size - inChunk)
            if (n <= 0) return null
            System.arraycopy(c, inChunk, out, done, n); done += n; pos += n
        }
        return out
    }
}
