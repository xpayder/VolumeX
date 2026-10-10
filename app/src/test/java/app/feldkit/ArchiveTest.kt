package app.feldkit

import app.feldkit.ui.screens.ArchiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Archive listing + extraction on files made by zip / tar / 7zz / gzip (copied to build/fixtures/archives). */
class ArchiveTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures", "archives")

    private fun check(name: String, prefix: String = "proj/") {
        val f = File(dir, name); assumeTrue("missing $f", f.exists())
        val s = ArchiveSession.fromFile(f, name)!!
        val items = s.list().filter { !it.isDir && !it.path.substringAfterLast('/').startsWith("._") }.associateBy { it.path.trimEnd('/') }
        assertEquals(name, setOf("${prefix}README.md", "${prefix}docs/data.bin", "${prefix}src/deep/a.txt", "${prefix}src/main.py"), items.keys)
        val out = ByteArrayOutputStream(); s.extract("${prefix}src/main.py", out)
        assertEquals("print(\"hi\")\n", out.toString())
        val big = ByteArrayOutputStream(); s.extract("${prefix}docs/data.bin", big)
        assertEquals(300000, big.size())
    }

    @Test fun zip() = check("proj.zip")
    @Test fun sevenZ() = check("proj.7z")
    @Test fun tarGz() = check("proj.tar.gz")
    @Test fun tarXz() = check("proj.tar.xz")
    @Test fun singleGz() {
        val f = File(dir, "readme.md.gz"); assumeTrue(f.exists())
        val s = ArchiveSession.fromFile(f, "readme.md.gz")!!
        assertEquals(listOf("readme.md"), s.list().map { it.path })
        val o = ByteArrayOutputStream(); s.extract("readme.md", o); assertTrue(o.toString().startsWith("readme"))
    }
}
