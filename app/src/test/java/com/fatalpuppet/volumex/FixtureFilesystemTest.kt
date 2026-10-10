package com.fatalpuppet.volumex

import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A raw disk image file exposed as a 512-byte-sector block device. */
class FileBlockDevice(private val file: File, private val writable: Boolean) : BlockDeviceReader {
    private var raf: RandomAccessFile? = null
    override fun open(): Boolean { raf = RandomAccessFile(file, if (writable) "rw" else "r"); return true }
    override fun close() { raf?.close(); raf = null }
    override fun isOpen() = raf != null
    override fun sectorSize() = 512
    override fun sectorCount() = file.length() / 512
    override fun readSector(lba: Long): ByteArray? {
        val r = raf ?: return null
        if (lba < 0 || (lba + 1) * 512 > file.length()) return null
        val b = ByteArray(512); r.seek(lba * 512); r.readFully(b); return b
    }
    override fun writeSector(lba: Long, data: ByteArray): Boolean {
        val r = raf ?: return false
        if (!writable || data.size != 512 || lba < 0 || (lba + 1) * 512 > file.length()) return false
        r.seek(lba * 512); r.write(data); return true
    }
}

/**
 * Runs the app's real mount/read code against raw GPT disk images created by the macOS tools
 * (tools/make-fixtures.sh). Skipped when the images are absent.
 */
@RunWith(Parameterized::class)
class FixtureFilesystemTest(private val name: String, private val expectedType: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf(
            arrayOf("apfs", "APFS"), arrayOf("hfs", "HFS+"),
            arrayOf("exfat", "exFAT"), arrayOf("exfatbig", "exFAT"), arrayOf("fat32", "FAT32"), arrayOf("ntfs", "NTFS")
        )
        private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    }

    private class Item(val sha: String, val size: Long, val path: String)

    private fun image() = File(dir, "$name.img").also { assumeTrue("fixture missing: $it", it.exists()) }
    private fun manifest() = File(dir, "$name.manifest").readLines().filter { it.isNotBlank() }.map {
        val p = it.split('\t'); Item(p[0], p[1].toLong(), p.drop(2).joinToString("\t"))
    }

    private fun mount(writable: Boolean = false, img: File = image()): Pair<FileSystemReader, BlockDeviceReader> {
        val dev = FileBlockDevice(img, writable).also { it.open() }
        val mounted = FilesystemMounter.mount(dev)
        assertNotNull("$name: no filesystem detected", mounted)
        return mounted!!.first to dev
    }

    private fun find(r: FileSystemReader, path: String): FileSystemEntry? {
        var dirPath = "/"
        var cur: FileSystemEntry? = null
        for (part in path.split('/')) {
            val list = r.listDirectory(0, dirPath)
            cur = list.firstOrNull { it.name == part } ?: return null
            dirPath = cur.path
        }
        return cur
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun detects_filesystem_and_volume() {
        val (r, _) = mount()
        val vols = r.getVolumeInfos()
        assertTrue("$name: no volumes", vols.isNotEmpty())
        assertEquals(expectedType, vols[0].type)
    }

    @Test fun every_file_reads_back_identically() {
        val (r, _) = mount()
        val failures = mutableListOf<String>()
        for (item in manifest()) {
            val e = find(r, item.path)
            if (e == null) { failures += "missing: ${item.path}"; continue }
            if (e.size != item.size) failures += "size ${item.path}: ${e.size} != ${item.size}"
            val data = r.readFile(e)
            if (data == null) failures += "unreadable: ${item.path}"
            else if (sha(data) != item.sha) failures += "content differs: ${item.path} (got ${data.size} bytes)"
        }
        assertTrue("$name: ${failures.size} of ${manifest().size} files wrong:\n" + failures.take(15).joinToString("\n"), failures.isEmpty())
    }

    @Test fun random_access_ranges_match_full_read() {
        val (r, _) = mount()
        val rnd = java.util.Random(11)
        for (path in listOf("big.bin", "odd4097.bin", "hello.txt")) {
            val e = find(r, path)!!
            val full = r.readFile(e)!!
            repeat(250) {
                val off = if (full.isEmpty()) 0L else rnd.nextInt(full.size).toLong()
                val len = 1 + rnd.nextInt(70000)
                val buf = ByteArray(len)
                val n = r.readRange(e, off, buf, 0, len)
                val expect = minOf(len.toLong(), full.size - off).toInt().coerceAtLeast(0)
                assertEquals("$name $path off=$off len=$len", expect, n)
                assertTrue("$name $path content off=$off len=$len", java.util.Arrays.equals(buf.copyOf(n), full.copyOfRange(off.toInt(), off.toInt() + n)))
            }
            assertEquals(0, r.readRange(e, full.size.toLong(), ByteArray(8), 0, 8))
        }
    }

    @Test fun large_directory_lists_all_300() {
        val (r, _) = mount()
        val many = find(r, "many")
        assertNotNull(many)
        val n = r.listDirectory(0, many!!.path).count { it.name.startsWith("file_") }
        assertEquals(if (name == "ntfs") 600 else 300, n)
    }

}

/** A disk with HFS+, APFS and exFAT partitions must expose all three (GPT-driven probing). */
class MultiPartitionTest {
    @Test fun all_three_partitions_are_found_and_readable() {
        val img = File(System.getProperty("fixtures.dir") ?: "build/fixtures", "multi.img")
        assumeTrue("fixture missing", img.exists())
        val dev = FileBlockDevice(img, false).also { it.open() }
        val parts = FilesystemMounter.mountAll(dev)
        val composite = com.fatalpuppet.volumex.storage.filesystem.CompositeReader(parts).also { it.mount() }
        val types = composite.getVolumeInfos().map { it.type }.sorted()
        assertEquals(listOf("APFS", "HFS+", "exFAT"), types)
        for (i in composite.getVolumeInfos().indices) {
            val marker = composite.listDirectory(i, "/").firstOrNull { it.name == "marker.txt" }
            assertNotNull("volume $i (${composite.getVolumeInfos()[i].type}) has no marker.txt", marker)
            assertTrue(String(composite.readFile(marker!!)!!).startsWith("marker MULTI"))
        }
    }
}
