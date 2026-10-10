package app.feldkit

import app.feldkit.hash.HashAlgo
import app.feldkit.offload.DestDir
import app.feldkit.offload.DestSink
import app.feldkit.offload.DestStatus
import app.feldkit.offload.Destination
import app.feldkit.offload.OffloadConfig
import app.feldkit.offload.OffloadEngine
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.VolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Random

/** The offload engine against an in-memory source and destinations (no native code, so only JCA hashes). */
class OffloadTest {
    private class MemSource(val files: Map<String, ByteArray>, val unreadable: Set<String> = emptySet()) : FileSystemReader {
        fun entry(path: String, dir: Boolean = false) = FileSystemEntry(path.substringAfterLast('/'), path, dir, files[path]?.size?.toLong() ?: 0L, 0, 0)
        override fun mount() = true
        override fun getVolumeInfos(): List<VolumeInfo> = emptyList()
        override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
            val prefix = if (path == "/") "/" else "$path/"
            val kids = LinkedHashMap<String, Boolean>()
            for (p in files.keys) if (p.startsWith(prefix)) { val rest = p.removePrefix(prefix); val first = rest.substringBefore('/'); kids[first] = rest.contains('/') }
            return kids.map { (n, isDir) -> entry(prefix + n, isDir) }
        }
        override fun readFile(entry: FileSystemEntry): ByteArray? = if (entry.path in unreadable) null else files[entry.path]
        override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
            if (entry.path in unreadable) return false
            val d = files[entry.path] ?: return false
            var off = 0
            while (off < d.size) { val n = minOf(65536, d.size - off); out.write(d, off, n); off += n; onProgress?.invoke(off.toLong()) }
            return true
        }
        override fun unmount() {}
    }

    private class MemDest(override val label: String, val corrupt: Boolean = false, val failCreate: Boolean = false) : Destination {
        val store = HashMap<String, ByteArray>()
        override fun dir(path: List<String>): DestDir = object : DestDir {
            val prefix = path.joinToString("/") + "/"
            override fun sizeOf(name: String) = store[prefix + name]?.size?.toLong()
            override fun create(name: String): DestSink {
                if (failCreate) throw java.io.IOException("destination is read-only")
                var real = name; var n = 1
                while (store.containsKey(prefix + real)) { val dot = name.lastIndexOf('.'); real = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"; n++ }
                val bos = java.io.ByteArrayOutputStream(); val key = prefix + real; val rn = real
                return object : DestSink {
                    override val name = rn
                    override fun write(buf: ByteArray, off: Int, len: Int) = bos.write(buf, off, len)
                    override fun finish() { val b = bos.toByteArray(); if (corrupt && b.isNotEmpty()) b[b.size / 2] = (b[b.size / 2].toInt() xor 0x55).toByte(); store[key] = b }
                    override fun abort() {}
                }
            }
            override fun open(name: String): InputStream? = store[prefix + name]?.let { ByteArrayInputStream(it) }
            override fun delete(name: String) { store.remove(prefix + name) }
        }
    }

    private fun data(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val cfg = OffloadConfig(algos = listOf(HashAlgo.SHA256, HashAlgo.MD5), jobFolder = "Job")

    private val tree = mapOf(
        "/DCIM/100/A001.mov" to data(3_000_000, 1),
        "/DCIM/100/A002.mov" to data(1_048_576, 2),
        "/DCIM/101/notes.txt" to data(1234, 3),
        "/empty.bin" to ByteArray(0),
    )

    @Test fun copiesToTwoDestinationsAndVerifiesBoth() {
        val src = MemSource(tree)
        val a = MemDest("phone"); val b = MemDest("sd")
        val engine = OffloadEngine(src, 0, listOf(a, b), cfg)
        val results = engine.run(listOf(src.entry("/DCIM", true), src.entry("/empty.bin")))
        assertEquals(4, results.size)
        for (r in results) {
            assertTrue("${r.path}: ${r.perDest.map { it.message }}", r.ok)
            assertTrue(r.perDest.all { it.status == DestStatus.VERIFIED })
        }
        val mov = results.first { it.path.endsWith("A001.mov") }
        assertEquals(sha(tree["/DCIM/100/A001.mov"]!!), mov.hashes!![HashAlgo.SHA256])
        for (d in listOf(a, b)) {
            assertEquals(tree["/DCIM/100/A001.mov"]!!.toList(), d.store["Job/DCIM/100/A001.mov"]!!.toList())
            assertEquals(0, d.store["Job/empty.bin"]!!.size)
        }
        assertTrue(engine.progress.finished); assertEquals(tree.values.sumOf { it.size.toLong() }, engine.progress.doneBytes)
    }

    @Test fun aCorruptedCopyIsDetectedAndRemovedWhileTheOtherIsFine() {
        val src = MemSource(tree)
        val good = MemDest("good"); val bad = MemDest("bad", corrupt = true)
        val results = OffloadEngine(src, 0, listOf(good, bad), cfg).run(listOf(src.entry("/DCIM/100/A001.mov")))
        val r = results.single()
        assertEquals(DestStatus.VERIFIED, r.perDest[0].status)
        assertEquals(DestStatus.MISMATCH, r.perDest[1].status)
        assertFalse(r.ok)
        assertTrue(good.store.containsKey("Job/A001.mov")); assertFalse("a bad copy must not stay behind", bad.store.containsKey("Job/A001.mov"))
    }

    @Test fun aDestinationThatCannotBeWrittenIsReportedAndTheRestContinue() {
        val src = MemSource(tree)
        val ok = MemDest("ok"); val ro = MemDest("readonly", failCreate = true)
        val results = OffloadEngine(src, 0, listOf(ok, ro), cfg).run(listOf(src.entry("/DCIM/101/notes.txt")))
        assertEquals(DestStatus.VERIFIED, results.single().perDest[0].status)
        assertEquals(DestStatus.ERROR, results.single().perDest[1].status)
        assertNotNull(results.single().perDest[1].message)
    }

    @Test fun existingFilesAreNeverOverwritten() {
        val src = MemSource(tree)
        val d = MemDest("phone")
        d.store["Job/notes.txt"] = "precious".toByteArray()
        val r = OffloadEngine(src, 0, listOf(d), cfg).run(listOf(src.entry("/DCIM/101/notes.txt"))).single()
        assertEquals("notes (1).txt", r.perDest[0].writtenName)
        assertEquals("precious", String(d.store["Job/notes.txt"]!!))
        assertEquals(tree["/DCIM/101/notes.txt"]!!.toList(), d.store["Job/notes (1).txt"]!!.toList())
    }

    @Test fun anUnreadableSourceFileFailsOnlyThatFile() {
        val src = MemSource(tree, unreadable = setOf("/DCIM/100/A002.mov"))
        val d = MemDest("phone")
        val results = OffloadEngine(src, 0, listOf(d), cfg).run(listOf(src.entry("/DCIM", true)))
        assertEquals(3, results.size)
        val bad = results.first { it.path.endsWith("A002.mov") }
        assertFalse(bad.ok); assertNotNull(bad.error)
        assertTrue(results.filter { it !== bad }.all { it.ok })
        assertFalse(d.store.containsKey("Job/DCIM/100/A002.mov"))
    }

    @Test fun withoutVerifyingFilesAreMarkedCopiedOnly() {
        val src = MemSource(tree)
        val d = MemDest("phone")
        val r = OffloadEngine(src, 0, listOf(d), OffloadConfig(algos = listOf(HashAlgo.MD5), verify = false, jobFolder = "Job")).run(listOf(src.entry("/DCIM/101/notes.txt"))).single()
        assertEquals(DestStatus.COPIED, r.perDest[0].status)
    }
}
