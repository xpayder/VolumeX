package com.fatalpuppet.volumex

import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * BitLocker volumes made by tools/make-bitlocker.py (throwaway password "VolumeX-Test-1"); the same images are opened by
 * cryptsetup and dislocker, so a pass here means the Kotlin reader agrees with those independent implementations.
 */
class BitLockerTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private val password = "VolumeX-Test-1"
    private val recovery = "012221-024442-036663-048884-061105-073326-085547-097768"

    private fun mount(name: String) = FilesystemMounter.mountAll(FileBlockDevice(File(dir, "$name.img").also { assumeTrue(it.exists()) }, false).also { it.open() }).single().reader
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun allFiles(r: com.fatalpuppet.volumex.storage.filesystem.FileSystemReader, path: String = "/"): List<FileSystemEntry> =
        r.listDirectory(0, path).flatMap { if (it.isDirectory) allFiles(r, it.path) else listOf(it) }

    private fun readEverything(name: String, secret: String) {
        val r = mount(name)
        assertTrue(r.isLocked(0))
        assertTrue(r.getVolumeInfos().single().isEncrypted)
        assertTrue(r.listDirectory(0, "/").isEmpty())
        assertTrue("$name: secret rejected", r.unlock(0, secret))
        assertFalse(r.isLocked(0))
        val expected = File(dir, "$name.manifest").readLines().filter { it.isNotBlank() }.associate { val p = it.split('\t'); p[2] to (p[0] to p[1].toLong()) }
        val files = allFiles(r).associateBy { it.path.trimStart('/') }
        assertEquals(expected.keys, files.keys.filter { !it.startsWith("System Volume") && !it.startsWith("lost+found") }.toSet())
        for ((p, e) in expected) {
            val data = r.readFile(files[p]!!)!!
            assertEquals(p, e.second, data.size.toLong()); assertEquals(p, e.first, sha(data))
        }
    }

    @Test fun ntfsXts128_password() = readEverything("bitlocker-ntfs", password)
    @Test fun ntfsXts128_recoveryKey() = readEverything("bitlocker-ntfs", recovery)
    @Test fun exfatToGoXts256_password() = readEverything("bitlocker-exfat", password)
    @Test fun ntfsCbc128_password() = readEverything("bitlocker-cbc", password)

    @Test fun wrongSecretsRejected() {
        val r = mount("bitlocker-ntfs")
        assertFalse(r.unlock(0, "not-the-password"))
        assertFalse(r.unlock(0, "012221-024442-036663-048884-061105-073326-085547-097769"))   // not divisible by 11
        assertFalse(r.unlock(0, "111111-024442-036663-048884-061105-073326-085547-097768"))   // valid shape, wrong key
        assertTrue(r.isLocked(0))
    }
}
