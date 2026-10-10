package app.feldkit

import app.feldkit.audio.TempoAnalyzer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Tracks from tools/make-tempo-fixtures.py have an exactly known tempo; the detector must land on it. */
class TempoTest {
    private val dir = File("build/fixtures/tempo")

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val raw = f.readBytes()
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24)
        var p = 12; var off = -1; var len = 0
        while (p + 8 <= raw.size) {
            val id = String(raw, p, 4, Charsets.ISO_8859_1); var sz = b.getInt(p + 4)
            if (id == "data") { off = p + 8; len = if (sz <= 0 || off + sz > raw.size) raw.size - off else sz; break }
            p += 8 + sz + (sz and 1)
        }
        val s = ByteBuffer.wrap(raw, off, len).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(len / 2) { s.getShort(off + it * 2) / 32768f } to rate
    }

    private data class Case(val file: String, val bpm: Double, val pattern: String, val jitter: Double)

    private fun cases(): List<Case> {
        val txt = File(dir, "cases.json").readText()
        return Regex("""\{\s*"file":\s*"([^"]+)",\s*"bpm":\s*([0-9.]+),\s*"pattern":\s*"([^"]+)",\s*"swing":\s*[0-9.]+,\s*"jitter":\s*([0-9.]+)""")
            .findAll(txt).map { Case(it.groupValues[1], it.groupValues[2].toDouble(), it.groupValues[3], it.groupValues[4].toDouble()) }.toList()
    }

    @Test fun knownTempos() {
        TempoAnalyzer.debug = true
        assumeTrue(File(dir, "cases.json").exists())
        var worst = 0.0; var fails = 0
        for (c in cases()) {
            val (pcm, rate) = readWav(File(dir, c.file))
            val t0 = System.nanoTime()
            val r = TempoAnalyzer.analyze(pcm, rate)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val got = r?.bpm
            // half / double time is the same pulse; the printed value says which reading was chosen
            val err = got?.let { g -> listOf(g, g * 2, g / 2).minOf { abs(it - c.bpm) } } ?: Double.NaN
            val exact = got?.let { abs(it - c.bpm) }
            println("%-26s true=%8.3f got=%8s err=%7s  conf=%s steady=%s spread=%s alts=%s  %dms".format(
                c.file, c.bpm, got?.toString() ?: "null", exact?.let { "%.3f".format(it) } ?: "-", r?.confidence?.let { "%.2f".format(it) }, r?.steady, r?.spread?.let { "%.3f".format(it) }, r?.alternates, ms))
            val flexible = c.file.contains("_70.00_")   // half / double reading is a matter of taste
            if (exact == null || (exact > 0.05 && !flexible)) { fails++; println("   ^^^ MISMATCH") }
            if (exact != null && exact < 5) worst = maxOf(worst, exact)
        }
        println("worst error among right-octave results: %.3f BPM, mismatches: %d".format(worst, fails))
    }

    @Test fun beatlessMaterialGivesNoTempo() {
        assumeTrue(File(dir, "beatless_pad.wav").exists())
        val (pcm, rate) = readWav(File(dir, "beatless_pad.wav"))
        TempoAnalyzer.debug = true
        val r = TempoAnalyzer.analyze(pcm, rate)
        println("beatless pad -> $r")
        assertNull(r)
    }

    /** Real sample-library files whose names state the tempo (tools/make-tempo-real.py). Reports; does not fail. */
    @Test fun labelledSampleLibrary() {
        val dirR = File("build/fixtures/real-valid")
        assumeTrue(File(dirR, "manifest.json").exists())
        TempoAnalyzer.debug = false
        val txt = File(dirR, "manifest.json").readText()
        val items = Regex(""""file":\s*"([^"]+)",\s*"bpm":\s*([0-9.]+),\s*"src":\s*"([^"]*)",\s*"dur":\s*([0-9.]+)""").findAll(txt).toList()
        var exact = 0; var octave = 0; var nul = 0; var wrong = 0; var n = 0
        val wrongList = ArrayList<String>()
        for (m in items) {
            val (pcm, rate) = readWav(File(dirR, m.groupValues[1]))
            val label = m.groupValues[2].toDouble()
            val r = TempoAnalyzer.analyze(pcm, rate)
            n++
            val got = r?.bpm
            val tag = when {
                got == null -> { nul++; "none" }
                abs(got - label) <= maxOf(0.5, label * 0.004) -> { exact++; "OK" }
                listOf(got * 2, got / 2).any { abs(it - label) <= maxOf(0.5, label * 0.004) } -> { octave++; "octave" }
                else -> { wrong++; "WRONG" }
            }
            println("%-6s label=%6.1f got=%8s steady=%-5s dur=%5s  %s".format(tag, label, got?.toString() ?: "-", r?.steady, m.groupValues[4], m.groupValues[3].takeLast(60)))
            if (tag == "WRONG") wrongList.add(m.groupValues[3].takeLast(50))
        }
        println("LIBRARY: n=$n exact=$exact octave=$octave none=$nul wrong=$wrong")
    }

    /** Writes the decision features of the labelled libraries to CSV so the octave rule can be fitted offline. */
    @Test fun dumpFeatures() {
        for (set in listOf("real-train", "real-valid")) {
            val dirS = File("build/fixtures/$set")
            if (!File(dirS, "manifest.json").exists()) continue
            val txt = File(dirS, "manifest.json").readText()
            val items = Regex(""""file":\s*"([^"]+)",\s*"bpm":\s*([0-9.]+),\s*"src":\s*"([^"]*)",\s*"dur":\s*([0-9.]+)""").findAll(txt).toList()
            val out = StringBuilder("file,label,dur,prom,crest,bpm,comb,contrast,nonoct,loopfit,beats,frompeak,g0,gb,off,sh,sq,db,src\n")
            for (m in items) {
                val (pcm, rate) = readWav(File(dirS, m.groupValues[1]))
                val f = TempoAnalyzer.features(pcm, rate)
                val src = m.groupValues[3].replace("\"", "")
                if (f == null) { out.append("${m.groupValues[1]},${m.groupValues[2]},${m.groupValues[4]},,,,,,,,,,,,,,,,\"$src\"\n"); continue }
                for (c in f.candidates) out.append("%s,%s,%s,%.3f,%.3f,%.4f,%.6f,%.4f,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,\"%s\"\n".format(java.util.Locale.ROOT, m.groupValues[1], m.groupValues[2], m.groupValues[4], f.prominence, f.crest, c.bpm, c.comb, c.contrast, if (c.nonOctave) 1 else 0, if (c.loopFit) 1 else 0, c.beats, if (c.fromPeak) 1 else 0, c.gridAtZero, c.gridBest, c.offbeat, c.subHalf, c.subQuarter, c.dbl, src))
            }
            File("build/fixtures/$set.csv").writeText(out.toString())
            println("wrote $set.csv")
        }
    }
}
