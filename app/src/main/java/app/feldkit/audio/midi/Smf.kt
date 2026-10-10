package app.feldkit.audio.midi

/** One played note, in ticks; [endTick] is the matching note-off (or the end of the track). */
class MidiNote(val track: Int, val tick: Long, val channel: Int, val key: Int, val velocity: Int, var endTick: Long)
class TempoChange(val tick: Long, val microsPerQuarter: Int)
class ControlChange(val tick: Long, val channel: Int, val controller: Int, val value: Int)
class ProgramChange(val tick: Long, val channel: Int, val program: Int)
class PitchBend(val tick: Long, val channel: Int, val semitones: Double)

/** A parsed Standard MIDI File with a tick -> seconds map. */
class Song(
    val division: Int,
    val smpteSecondsPerTick: Double,
    val notes: List<MidiNote>,
    val tempoChanges: List<TempoChange>,
    val controls: List<ControlChange>,
    val programs: List<ProgramChange>,
    val bends: List<PitchBend>,
    val trackNames: Map<Int, String>,
    val timeSignature: Pair<Int, Int>?,
) {
    private val segTick = LongArray(tempoChanges.size + 1)
    private val segSec = DoubleArray(tempoChanges.size + 1)
    private val segSpt = DoubleArray(tempoChanges.size + 1)   // seconds per tick in each segment

    init {
        var spt = if (division > 0) 500_000.0 / 1e6 / division else smpteSecondsPerTick
        segTick[0] = 0; segSec[0] = 0.0; segSpt[0] = spt
        var tick = 0L; var sec = 0.0
        for ((i, t) in tempoChanges.withIndex()) {
            sec += (t.tick - tick) * spt; tick = t.tick
            if (division > 0) spt = t.microsPerQuarter / 1e6 / division
            segTick[i + 1] = tick; segSec[i + 1] = sec; segSpt[i + 1] = spt
        }
    }

    /** Seconds for an absolute tick position (the tempo in force at the start of the file is 120 BPM until a change says otherwise). */
    fun seconds(tick: Long): Double {
        var i = segTick.size - 1
        while (i > 0 && segTick[i] > tick) i--
        return segSec[i] + (tick - segTick[i]) * segSpt[i]
    }

    val lengthSeconds: Double get() = notes.maxOfOrNull { seconds(it.endTick) } ?: 0.0
}

object Smf {
    fun parse(b: ByteArray): Song? {
        var p = 0
        // RIFF-wrapped MIDI (.rmi)
        if (b.size > 20 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF") {
            val idx = indexOf(b, "MThd"); if (idx < 0) return null; p = idx
        }
        if (b.size < p + 14 || String(b, p, 4, Charsets.ISO_8859_1) != "MThd") return null
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        val hlen = u32(p + 4).toInt()
        val nTracks = u16(p + 10)
        val rawDiv = u16(p + 12)
        val division: Int; val smpte: Double
        if (rawDiv and 0x8000 != 0) {
            val fps = 256 - ((rawDiv shr 8) and 0xFF); val tpf = rawDiv and 0xFF
            division = 0; smpte = 1.0 / (fps.coerceAtLeast(1) * tpf.coerceAtLeast(1))
        } else { division = rawDiv.coerceAtLeast(1); smpte = 0.0 }
        p += 8 + hlen

        val notes = ArrayList<MidiNote>(); val tempos = ArrayList<TempoChange>(); val ctl = ArrayList<ControlChange>()
        val progs = ArrayList<ProgramChange>(); val bends = ArrayList<PitchBend>(); val names = HashMap<Int, String>()
        var ts: Pair<Int, Int>? = null
        var track = 0
        while (track < nTracks && p + 8 <= b.size) {
            if (String(b, p, 4, Charsets.ISO_8859_1) != "MTrk") { p += 8 + u32(p + 4).toInt(); continue }
            val len = u32(p + 4).toInt(); var q = p + 8; val end = minOf(b.size, q + len)
            var tick = 0L; var status = 0
            val open = HashMap<Int, MidiNote>()    // (channel shl 8 | key) -> note
            fun vlq(): Long { var v = 0L; var c: Int; do { if (q >= end) return v; c = b[q++].toInt() and 0xFF; v = (v shl 7) or (c and 0x7F).toLong() } while (c and 0x80 != 0); return v }
            while (q < end) {
                tick += vlq()
                if (q >= end) break
                var st = b[q].toInt() and 0xFF
                if (st < 0x80) st = status else { q++; if (st < 0xF0) status = st }
                when {
                    st == 0xFF -> {
                        if (q >= end) break
                        val type = b[q++].toInt() and 0xFF; val ml = vlq().toInt()
                        if (q + ml > end) break
                        when (type) {
                            0x51 -> if (ml == 3) tempos.add(TempoChange(tick, ((b[q].toInt() and 0xFF) shl 16) or ((b[q + 1].toInt() and 0xFF) shl 8) or (b[q + 2].toInt() and 0xFF)))
                            0x03 -> names.putIfAbsent(track, String(b, q, ml, Charsets.ISO_8859_1).trim())
                            0x58 -> if (ml >= 2 && ts == null) ts = (b[q].toInt() and 0xFF) to (1 shl (b[q + 1].toInt() and 0xFF))
                        }
                        q += ml
                        if (type == 0x2F) break
                    }
                    st == 0xF0 || st == 0xF7 -> { val sl = vlq().toInt(); q += sl }
                    else -> {
                        val kind = st and 0xF0; val ch = st and 0x0F
                        val d1 = if (q < end) b[q++].toInt() and 0x7F else break
                        val d2 = if (kind != 0xC0 && kind != 0xD0) { if (q < end) b[q++].toInt() and 0x7F else break } else 0
                        when (kind) {
                            0x90 -> if (d2 > 0) {
                                open[(ch shl 8) or d1]?.let { it.endTick = tick }      // retrigger closes the previous one
                                val n = MidiNote(track, tick, ch, d1, d2, tick); notes.add(n); open[(ch shl 8) or d1] = n
                            } else open.remove((ch shl 8) or d1)?.endTick = tick
                            0x80 -> open.remove((ch shl 8) or d1)?.endTick = tick
                            0xB0 -> ctl.add(ControlChange(tick, ch, d1, d2))
                            0xC0 -> progs.add(ProgramChange(tick, ch, d1))
                            0xE0 -> bends.add(PitchBend(tick, ch, ((d2 shl 7 or d1) - 8192) / 8192.0 * 2.0))
                        }
                    }
                }
            }
            for (n in open.values) n.endTick = maxOf(n.endTick, tick)
            p = end; track++
        }
        if (notes.isEmpty()) return null
        tempos.sortBy { it.tick }
        // a tempo change at tick 0 replaces the 120 BPM default; keep the list free of exact duplicates
        val cleaned = ArrayList<TempoChange>()
        for (t in tempos) if (cleaned.isNotEmpty() && cleaned.last().tick == t.tick) cleaned[cleaned.size - 1] = t else cleaned.add(t)
        notes.sortBy { it.tick }
        for (n in notes) if (n.endTick <= n.tick) n.endTick = n.tick + maxOf(1, division / 8)
        return Song(division, smpte, notes, cleaned, ctl, progs, bends, names, ts)
    }

    private fun indexOf(b: ByteArray, s: String): Int {
        val k = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..b.size - k.size) { for (j in k.indices) if (b[i + j] != k[j]) continue@outer; return i }
        return -1
    }
}
