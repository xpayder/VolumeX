package com.fatalpuppet.volumex

import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** FileVault-encrypted APFS image from tools/make-fixtures.sh (throwaway password "secret123"). */
class FileVaultTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private val img = File(dir, "filevault.img")

    private fun mount() = FilesystemMounter.mountAll(FileBlockDevice(img, false).also { it.open() }).single().reader

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun lockedUntilUnlocked() {
        assumeTrue(img.exists())
        val r = mount()
        assertTrue(r.isLocked(0))
        assertTrue(r.getVolumeInfos().single().isEncrypted)
        assertTrue(r.listDirectory(0, "/").isEmpty())
    }

    @Test fun wrongPasswordRejected() {
        assumeTrue(img.exists())
        val r = mount()
        assertFalse(r.unlock(0, "not-the-password"))
        assertTrue(r.isLocked(0))
    }

    @Test fun unlockAndReadEverything() {
        assumeTrue(img.exists())
        val r = mount()
        assertTrue(r.unlock(0, "secret123"))
        assertFalse(r.isLocked(0))
        val expected = File(dir, "filevault.manifest").readLines().filter { it.isNotBlank() }.associate { val p = it.split('\t'); p[2] to (p[0] to p[1].toLong()) }
        fun walk(path: String): List<FileSystemEntry> = r.listDirectory(0, path).filter { !it.name.startsWith(".") }.flatMap { if (it.isDirectory) walk(it.path) else listOf(it) }
        val files = walk("/").associateBy { it.path.trimStart('/') }
        assertEquals(expected.keys, files.keys)
        for ((p, e) in files) {
            val data = r.readFile(e)!!
            assertEquals(p, expected[p]!!.second, data.size.toLong())
            assertEquals(p, expected[p]!!.first, sha(data))
        }
        // random access through the decrypting path
        val big = files["big.bin"]!!; val all = r.readFile(big)!!
        val buf = ByteArray(5000)
        assertEquals(5000, r.readRange(big, 1_234_567, buf, 0, 5000))
        assertArrayEquals(all.copyOfRange(1_234_567, 1_239_567), buf)
    }
}
