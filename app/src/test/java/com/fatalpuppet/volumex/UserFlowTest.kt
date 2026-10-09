package com.fatalpuppet.volumex

import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

/** Replays what the app does: create a folder, open it, add a file to it, browse back. */
class UserFlowTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")

    // Same walk as FileBrowserViewModel.resolveDirEntry
    private fun resolveDirEntry(r: FileSystemReader, path: String): FileSystemEntry? {
        var cur = r.rootEntry(0); var p = "/"
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = r.listDirectory(0, p).firstOrNull { it.name == part && it.isDirectory } ?: return null
            p = cur.path
        }
        return cur
    }

    private fun flow(imageName: String) {
        val src = File(dir, "$imageName.img"); assumeTrue("fixture missing", src.exists())
        val img = File(dir, "flow-$imageName.img"); img.delete()
        ProcessBuilder("cp", "-c", src.path, img.path).inheritIO().start().waitFor()
        val dev = FileBlockDevice(img, true).also { it.open() }
        val (reader, writer) = FilesystemMounter.mount(dev)!!
        val w = writer!!

        assertTrue("createDirectory", w.createDirectory(reader.rootEntry(0), "VX_TEST"))
        val listed = reader.listDirectory(0, "/")
        assertNotNull("VX_TEST not listed in root", listed.firstOrNull { it.name == "VX_TEST" })

        // user taps the folder: browser lists it
        val inside = reader.listDirectory(0, "/VX_TEST")
        assertEquals("new folder should be empty", 0, inside.size)

        // user taps "+" -> Add files
        val parent = resolveDirEntry(reader, "/VX_TEST")
        assertNotNull("resolveDirEntry(/VX_TEST) is null", parent)
        val data = ByteArray(5_000_000).also { Random(7).nextBytes(it) }
        assertTrue("writeFileStream into folder", w.writeFileStream(parent!!, "movie.bin", data.size.toLong(), ByteArrayInputStream(data)))

        val after = reader.listDirectory(0, "/VX_TEST")
        assertEquals(listOf("movie.bin"), after.map { it.name })
        val back = ByteArrayOutputStream2()
        assertTrue(reader.readFileTo(after[0], back))
        assertTrue("content differs", back.toByteArray().contentEquals(data))

        // nested folder + file in it
        val sub = resolveDirEntry(reader, "/VX_TEST")!!
        assertTrue(w.createDirectory(sub, "inner"))
        val inner = resolveDirEntry(reader, "/VX_TEST/inner")
        assertNotNull("nested folder not resolvable", inner)
        assertTrue(w.writeFile(inner!!, "n.txt", "nested".toByteArray()))
        assertEquals("nested", String(reader.readFile(reader.listDirectory(0, "/VX_TEST/inner")[0])!!))
        // original content untouched
        assertTrue(reader.listDirectory(0, "/").any { it.name == "Android" } || imageName != "exfat128")
    }

    @Test fun exfat_128k_clusters() = flow("exfat128")
    @Test fun exfat_small() = flow("exfat")
    @Test fun fat32() = flow("fat32")
}

private class ByteArrayOutputStream2 : java.io.ByteArrayOutputStream()
