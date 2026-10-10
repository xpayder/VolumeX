package app.feldkit

import app.feldkit.audio.midi.MidiClassifier
import app.feldkit.audio.midi.MidiRenderer
import app.feldkit.audio.midi.Smf
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MidiTest {
    private val list = File("build/fixtures/midi-list.tsv")

    /** Files whose path says "drum" must come out with a drum channel; "melody / chord / bass" must not. */
    @Test fun drumVersusTonalOnLibraryFiles() {
        assumeTrue(list.exists())
        var drumOk = 0; var drumN = 0; var tonalOk = 0; var tonalN = 0; var unreadable = 0
        val bad = ArrayList<String>()
        for (line in list.readLines()) {
            val (label, path) = line.split('\t')
            val song = Smf.parse(File(path).readBytes())
            if (song == null) { unreadable++; continue }
            val verdict = MidiClassifier.classify(song, path.split('/').takeLast(2).joinToString(" "))
            val hasDrums = verdict.drums.isNotEmpty()
            val allDrum = verdict.drums.size == song.notes.map { it.channel }.distinct().size
            if (label == "drum") { drumN++; if (hasDrums) drumOk++ else bad.add("MISSED drum: " + path.takeLast(70)) }
            else { tonalN++; if (!allDrum && !(hasDrums && verdict.drums.size >= 1 && song.notes.map { it.channel }.distinct().size == 1)) tonalOk++ else bad.add("FALSE drum: " + path.takeLast(70) + " " + verdict.reason) }
        }
        bad.take(25).forEach(::println)
        println("MIDI drums detected %d/%d, tonal kept melodic %d/%d, unreadable %d".format(drumOk, drumN, tonalOk, tonalN, unreadable))
    }

    /** Renders a few library files to WAV so the timbre can be inspected (spectrogram) and the levels checked. */
    @Test fun renderSamples() {
        assumeTrue(list.exists())
        val out = File("build/fixtures/midi-out").also { it.mkdirs() }
        var n = 0
        for (label in listOf("drum", "tonal")) for (line in list.readLines().filter { it.startsWith(label) }.take(4)) {
            val path = line.split('\t')[1]
            val song = Smf.parse(File(path).readBytes()) ?: continue
            val v = MidiClassifier.classify(song, path.split('/').takeLast(2).joinToString(" "))
            val r = MidiRenderer(song, v.drums, v.remap)
            val t0 = System.nanoTime()
            val bb = ByteBuffer.allocate(r.totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            var peak = 0
            for (b in 0 until r.blockCount) for (s in r.render(b)) { bb.putShort(s); peak = maxOf(peak, kotlin.math.abs(s.toInt())) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val name = "%s_%d.wav".format(label, n++)
            File(out, name).outputStream().use { o ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + bb.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(r.sampleRate).putInt(r.sampleRate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(bb.capacity())
                o.write(h.array()); o.write(bb.array())
            }
            println("%s %-8s notes=%d drums=%s remap=%s dur=%.1fs peak=%.2f render=%dms  %s".format(name, label, song.notes.size, v.drums, v.remap, r.durationSeconds, peak / 32767.0, ms, path.takeLast(60)))
        }
    }

    /** Renders the hand-made files in build/fixtures/midi-test for spectral checks. */
    @Test fun renderToneFiles() {
        val dir = File("build/fixtures/midi-test"); assumeTrue(dir.exists())
        for (f in dir.listFiles { x -> x.name.endsWith(".mid") }!!) {
            val song = Smf.parse(f.readBytes())!!
            val v = MidiClassifier.classify(song, f.name)
            val r = MidiRenderer(song, v.drums, v.remap)
            val bb = ByteBuffer.allocate(r.totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (b in 0 until r.blockCount) for (s in r.render(b)) bb.putShort(s)
            File(dir, f.nameWithoutExtension + ".wav").outputStream().use { o ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + bb.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(r.sampleRate).putInt(r.sampleRate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(bb.capacity())
                o.write(h.array()); o.write(bb.array())
            }
            println("tone ${f.name}: drums=${v.drums} ${v.reason} dur=${r.durationSeconds}")
        }
    }
}
