package com.fatalpuppet.volumex

import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Exercises the writers on a *copy* of the macOS-made images and leaves the result in
 * build/fixtures/written-<name>.img plus .expect (sha256, size, path). tools/verify-written.sh then asks
 * macOS itself (fsck + mount + compare) whether the result is a valid filesystem with the right content.
 */
@RunWith(Parameterized::class)
class WriteFixtureTest(private val name: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf("hfs", "exfat", "exfatbig", "fat32")
        private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun root(r: com.fatalpuppet.volumex.storage.filesystem.FileSystemReader): FileSystemEntry = when (name) {
        "hfs" -> FileSystemEntry("/", "/", true, 0, 0, 0, hfsCatalogId = 2)
        "exfat", "exfatbig" -> FileSystemEntry("/", "/", true, 0, 0, 0, inodeOid = (r as com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatReader).getBootSector()!!.rootDirectoryCluster.toLong())
        else -> FileSystemEntry("/", "/", true, 0, 0, 0, inodeOid = (r as com.fatalpuppet.volumex.storage.filesystem.fat32.Fat32Reader).getVolumeHeader()!!.rootCluster)
    }

    @Test fun write_operations_succeed_and_are_recorded() {
        val src = File(dir, "$name.img"); assumeTrue("fixture missing", src.exists())
        val img = File(dir, "written-$name.img"); img.delete()
        ProcessBuilder("cp", "-c", src.path, img.path).inheritIO().start().waitFor().also { if (it != 0) src.copyTo(img, overwrite = true) }
        val dev = FileBlockDevice(img, true).also { it.open() }
        val (reader, writer) = FilesystemMounter.mount(dev)!!
        assertTrue("$name: writer not available", writer != null)
        val w = writer!!
        val rootE = root(reader)

        val small = "hello write ✓\n".toByteArray()
        val big = ByteArray(3 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
        val inner = "inside dir\n".toByteArray()
        val expected = linkedMapOf<String, ByteArray>()
        val failures = mutableListOf<String>()
        fun step(label: String, ok: () -> Boolean) { val r = try { ok() } catch (e: Throwable) { failures += "$label threw $e"; return }; if (!r) failures += "$label returned false" }

        step("writeFile small") { w.writeFile(rootE, "vx_small.txt", small).also { if (it) expected["vx_small.txt"] = small } }
        step("writeFile big") { w.writeFile(rootE, "vx_big.bin", big).also { if (it) expected["vx_big.bin"] = big } }
        step("createDirectory") { w.createDirectory(rootE, "vx_dir") }
        val dirEntry = reader.listDirectory(0, "/").firstOrNull { it.name == "vx_dir" }
        if (dirEntry == null) failures += "vx_dir not listed after createDirectory"
        else step("writeFile inner") { w.writeFile(dirEntry, "inner.txt", inner).also { if (it) expected["vx_dir/inner.txt"] = inner } }
        val toRename = reader.listDirectory(0, "/").firstOrNull { it.name == "vx_small.txt" }
        if (toRename == null) failures += "vx_small.txt not listed"
        else step("renameEntry") { w.renameEntry(toRename, "vx_renamed.txt").also { if (it) { expected["vx_renamed.txt"] = small; expected.remove("vx_small.txt") } } }
        val toDelete = reader.listDirectory(0, "/").firstOrNull { it.name == "hello.txt" }
        if (toDelete == null) failures += "hello.txt not listed"
        else step("deleteEntry") { w.deleteEntry(toDelete) }
        dev.flushCache(); dev.close()

        File(dir, "written-$name.expect").writeText(
            expected.entries.joinToString("\n") { "${sha(it.value)}\t${it.value.size}\t${it.key}" } + "\n" + "DELETED\thello.txt\n"
        )

        // Re-read through the app's own reader on a fresh mount.
        val dev2 = FileBlockDevice(img, false).also { it.open() }
        val (r2, _) = FilesystemMounter.mount(dev2)!!
        for ((path, bytes) in expected) {
            var cur: FileSystemEntry? = null; var p = "/"
            for (part in path.split('/')) { cur = r2.listDirectory(0, p).firstOrNull { it.name == part }; if (cur == null) break; p = cur.path }
            val got = cur?.let { r2.readFile(it) }
            if (got == null) failures += "re-read missing: $path" else if (!got.contentEquals(bytes)) failures += "re-read differs: $path (${got.size} vs ${bytes.size})"
        }
        if (r2.listDirectory(0, "/").any { it.name == "hello.txt" }) failures += "hello.txt still present after delete"
        assertTrue("$name write problems:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
