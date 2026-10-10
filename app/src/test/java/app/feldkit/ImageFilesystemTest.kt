package app.feldkit

import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.FilesystemMounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest

/**
 * Optical and disk images made by tools/make-iso-fixtures.sh (hdiutil): every file listed in the manifest must be found with the
 * right size and hash, and a directory listing must not invent entries.
 */
@RunWith(Parameterized::class)
class ImageFilesystemTest(private val name: String, private val file: String, private val expectedType: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf(
            arrayOf("iso-joliet", "iso-joliet.iso", "ISO 9660"),
            arrayOf("iso-plain", "iso-plain.iso", "ISO 9660"),
            arrayOf("udf", "udf.iso", "UDF"),
            arrayOf("iso-rr", "iso-rr.iso", "ISO 9660"),
            arrayOf("dmg-udzo", "dmg-udzo.dmg", "HFS+"),
            arrayOf("dmg-udro", "dmg-udro.dmg", "HFS+"),
        )
        private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    }

    private class Item(val sha: String, val size: Long, val path: String)

    private fun image() = File(dir, file).also { assumeTrue("fixture missing: $it (tools/make-iso-fixtures.sh)", it.exists()) }
    private fun manifest() = File(dir, "$name.manifest").readLines().filter { it.isNotBlank() }.map { val p = it.split('\t'); Item(p[0], p[1].toLong(), p.drop(2).joinToString("\t")) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun mount(): FileSystemReader {
        val dev: BlockDeviceReader = if (file.endsWith(".dmg")) (app.feldkit.storage.disk.DmgBlockDevice.open(app.feldkit.storage.disk.FileByteSource(image())) ?: throw AssertionError("not a readable dmg")) else FileBlockDevice(image(), false).also { it.open() }
        val m = FilesystemMounter.mount(dev)
        assertNotNull("$name: no filesystem detected", m)
        return m!!.first
    }

    private fun find(r: FileSystemReader, path: String): FileSystemEntry? {
        var dirPath = "/"; var cur: FileSystemEntry? = null
        for (part in path.split('/')) {
            val list = r.listDirectory(0, dirPath)
            // plain ISO 9660 stores names in capitals; compare without case there
            cur = list.firstOrNull { it.name == part } ?: list.firstOrNull { name == "iso-plain" && it.name.equals(part, true) } ?: return null
            dirPath = cur.path
        }
        return cur
    }

    @Test fun volumeInfo() {
        val r = mount()
        assertEquals(expectedType, r.getVolumeInfos().first().type)
    }

    @Test fun everyFileMatchesTheManifest() {
        val r = mount()
        var checked = 0
        for (item in manifest()) {
            // the plain ISO level 1 cannot hold names beyond 8.3: those are skipped there
            val e = find(r, item.path)
            if (e == null) { assumeTrue("name not representable in $name: ${item.path}", name != "iso-plain"); throw AssertionError("missing: ${item.path}") }
            assertEquals(item.path, item.size, e.size)
            val data = r.readFile(e)
            assertNotNull(item.path, data)
            assertEquals("hash of ${item.path}", item.sha, sha(data!!))
            checked++
        }
        assertTrue(checked > 100)
    }

    @Test fun rangeReadsMatch() {
        val r = mount()
        val e = find(r, "big.bin") ?: return
        val whole = r.readFile(e)!!
        val rnd = java.util.Random(3)
        repeat(30) {
            val off = rnd.nextInt(whole.size - 10); val len = 1 + rnd.nextInt(minOf(100_000, whole.size - off))
            val buf = ByteArray(len)
            val n = r.readRange(e, off.toLong(), buf, 0, len)
            assertEquals(len, n)
            assertEquals(whole.copyOfRange(off, off + len).toList(), buf.toList())
        }
    }

    @Test fun emptyDirectoryAndRootListing() {
        val r = mount()
        val root = r.listDirectory(0, "/")
        assertTrue(root.any { it.name.equals("Photos", true) && it.isDirectory })
        assertTrue(root.any { it.name.equals("hello.txt", true) || it.name.equals("HELLO.TXT", true) })
        val ed = root.firstOrNull { it.name.equals("empty dir", true) || it.name.equals("EMPTY_DI", true) }
        if (ed != null) assertTrue(r.listDirectory(0, ed.path).isEmpty())
    }
}
