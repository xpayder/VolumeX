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
import java.io.File
import java.security.MessageDigest

/** LUKS containers and LVM volumes made by tools/linux/make-luks-lvm.sh with the real Linux tools (cryptsetup, lvm2, mkfs.ext4). */
class LinuxVolumesTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private val pass = "FeldKit-Test-1"
    private class Item(val sha: String, val size: Long, val path: String)

    private fun image(n: String) = File(dir, n).also { assumeTrue("fixture missing: $it (tools/linux/make-luks-lvm.sh)", it.exists()) }
    private fun manifest(n: String) = File(dir, n).readLines().filter { it.isNotBlank() }.map { val p = it.split('\t'); Item(p[0], p[1].toLong(), p.drop(2).joinToString("\t")) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun find(r: FileSystemReader, path: String): FileSystemEntry? {
        var dirPath = "/"; var cur: FileSystemEntry? = null
        for (part in path.split('/')) { cur = r.listDirectory(0, dirPath).firstOrNull { it.name == part } ?: return null; dirPath = cur.path }
        return cur
    }

    private fun verify(r: FileSystemReader, items: List<Item>) {
        for (it in items) {
            val e = find(r, it.path); assertNotNull("missing ${it.path}", e)
            assertEquals(it.path, it.size, e!!.size)
            assertEquals("hash of ${it.path}", it.sha, sha(r.readFile(e)!!))
        }
    }

    @Test fun lvmFirstLogicalVolume() {
        val dev = FileBlockDevice(image("lvm.img"), false).also { it.open() }
        val mounted = FilesystemMounter.mount(dev)
        assertNotNull("no filesystem found in the LVM volume", mounted)
        verify(mounted!!.first, manifest("lvm-first.manifest"))
    }

    @Test fun lvmSecondLogicalVolume() {
        val dev = FileBlockDevice(image("lvm.img"), false).also { it.open() }
        val all = FilesystemMounter.mountAll(dev)
        assertEquals("both logical volumes must be mounted", 2, all.size)
        verify(all[1].reader, manifest("lvm-second.manifest"))
    }

    private fun luks(name: String) {
        val raw = FileBlockDevice(image("$name.img"), false).also { it.open() }
        val mounted = FilesystemMounter.mount(raw)
        assertNotNull("$name: not recognised as LUKS", mounted)
        val r = mounted!!.first
        assertTrue("$name: must start locked", r.isLocked(0))
        assertTrue("wrong passphrase must not unlock", !r.unlock(0, "not the passphrase"))
        assertTrue("$name: could not unlock with the right passphrase", r.unlock(0, pass))
        verify(r, manifest("$name.manifest"))
    }

    @Test fun luks1Xts() = luks("luks1-xts")
    @Test fun luks1Cbc() = luks("luks1-cbc")
    @Test fun luks2Pbkdf2() = luks("luks2-pbkdf2")
    @Test fun luks2Argon2id() = luks("luks2-argon")
    @Test fun luks2Argon2idOneGiB() = luks("luks2-1gib")
}
