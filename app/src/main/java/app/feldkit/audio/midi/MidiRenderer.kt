package app.feldkit.audio.midi

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Software synthesiser for MIDI clips: every pitched part sounds as an acoustic-style piano, every drum part as a General MIDI
 * drum kit. Both are generated from closed-form expressions of time (no state between samples), so any 2-second block can be
 * rendered on demand: playback starts at once and seeking costs nothing.
 *
 * Piano: per string, partials with a slight stretch (inharmonicity), hammer-position comb, a fast-then-slow two-stage decay
 * that is shorter for higher partials and higher keys, 1-3 detuned strings per key (beating), a hammer thump, damper release
 * and sustain-pedal handling.
 */
class MidiRenderer(private val song: Song, private val drumChannels: Set<Int>, private val remap: Map<Int, Int> = emptyMap(), val sampleRate: Int = 32000) {
    private class Voice(
        val startFrame: Int, val offFrame: Int, val endFrame: Int, val key: Int, val vel: Int,
        val channel: Int, val drum: Boolean, val gain: Float, val panL: Float, val panR: Float, val bend: Double, val seed: Int,
    )

    val blockFrames = sampleRate * 2
    private val voices = ArrayList<Voice>()
    val totalFrames: Int
    val blockCount: Int
    private val perBlock: Array<MutableList<Int>>

    init {
        // controller timelines per channel
        fun series(ch: Int, cc: Int) = song.controls.filter { it.channel == ch && it.controller == cc }.sortedBy { it.tick }
        val volS = HashMap<Int, List<ControlChange>>(); val exprS = HashMap<Int, List<ControlChange>>(); val panS = HashMap<Int, List<ControlChange>>(); val pedS = HashMap<Int, List<ControlChange>>()
        for (ch in song.notes.map { it.channel }.distinct()) { volS[ch] = series(ch, 7); exprS[ch] = series(ch, 11); panS[ch] = series(ch, 10); pedS[ch] = series(ch, 64) }
        fun valueAt(s: List<ControlChange>?, tick: Long, def: Int): Int { var v = def; if (s != null) for (e in s) { if (e.tick <= tick) v = e.value else break }; return v }
        val bendByCh = song.bends.groupBy { it.channel }.mapValues { e -> e.value.sortedBy { it.tick } }
        fun bendAt(ch: Int, tick: Long): Double { var v = 0.0; bendByCh[ch]?.let { for (e in it) { if (e.tick <= tick) v = e.semitones else break } }; return v }

        var maxEnd = 0
        for ((idx, n) in song.notes.withIndex()) {
            val drum = n.channel in drumChannels
            val start = (song.seconds(n.tick) * sampleRate).toInt()
            var endSec = song.seconds(n.endTick)
            if (!drum) {
                // sustain pedal held at note-off keeps the string ringing until the pedal comes up
                val ped = pedS[n.channel]
                if (ped != null && valueAt(ped, n.endTick, 0) >= 64) {
                    val up = ped.firstOrNull { it.tick >= n.endTick && it.value < 64 }
                    endSec = if (up != null) song.seconds(up.tick) else endSec + 4.0
                }
            }
            val off = (endSec * sampleRate).toInt().coerceAtLeast(start + 1)
            val tail = if (drum) (sampleRate * 2.2).toInt() else (sampleRate * (0.35 + 8.0 * (0.07 + 0.26 * (1.0 - n.key / 108.0)))).toInt()
            val end = off + tail
            val vol = valueAt(volS[n.channel], n.tick, 100) / 127.0
            val expr = valueAt(exprS[n.channel], n.tick, 127) / 127.0
            val pan = (valueAt(panS[n.channel], n.tick, 64) - 64) / 64.0
            val keyPan = if (drum) 0.0 else ((n.key - 64) / 64.0) * 0.22
            val p = ((pan + keyPan).coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0
            voices.add(Voice(start, off, end, if (drum) (remap[n.channel] ?: n.key) else n.key, n.velocity, n.channel, drum, (vol * expr).pow(1.5).toFloat(), cos(p).toFloat(), sin(p).toFloat(), bendAt(n.channel, n.tick), idx * 7919 + 13))
            maxEnd = max(maxEnd, off + (sampleRate * 1.2).toInt())
        }
        totalFrames = maxEnd + sampleRate    // a second of room for the tails
        blockCount = (totalFrames + blockFrames - 1) / blockFrames
        perBlock = Array(blockCount) { ArrayList<Int>() }
        for ((i, v) in voices.withIndex()) {
            val b0 = (v.startFrame / blockFrames).coerceAtMost(blockCount - 1); val b1 = ((v.endFrame - 1) / blockFrames).coerceAtMost(blockCount - 1)
            for (b in b0..b1) perBlock[b].add(i)
        }
    }

    val durationSeconds: Double get() = totalFrames.toDouble() / sampleRate

    /** Interleaved stereo 16-bit samples of block [index]. */
    fun render(index: Int): ShortArray {
        val n = if (index == blockCount - 1) totalFrames - index * blockFrames else blockFrames
        val left = FloatArray(n); val right = FloatArray(n)
        val base = index * blockFrames
        for (vi in perBlock[index]) {
            val v = voices[vi]
            if (v.drum) drum(v, base, n, left, right) else piano(v, base, n, left, right)
        }
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            out[2 * i] = (tanh(left[i] * 1.1) * 0.92 * 32767).toInt().toShort()
            out[2 * i + 1] = (tanh(right[i] * 1.1) * 0.92 * 32767).toInt().toShort()
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------------------------
    // piano

    private fun pianoTau(key: Int) = 12.0 * exp(-0.032 * (key - 21))

    private fun piano(v: Voice, base: Int, n: Int, left: FloatArray, right: FloatArray) {
        val from = max(base, v.startFrame); val to = min(base + n, v.endFrame)
        if (from >= to) return
        val velN = (v.vel / 127.0)
        val key = v.key
        val f0 = 440.0 * 2.0.pow((key - 69 + v.bend) / 12.0)
        if (f0 * 1.0 > sampleRate * 0.45) return
        val b = 4e-5 * exp(0.065 * (key - 21))                         // inharmonicity grows toward the treble
        val strings = if (key < 31) 1 else if (key < 54) 2 else 3
        val detune = doubleArrayOf(0.0, 0.9, -0.8)
        val tau0 = pianoTau(key)
        val pe = 1.55 - 0.85 * velN                                     // harder playing: brighter spectrum
        val kMax = min(18, ((sampleRate * 0.45) / f0).toInt())
        val amps = DoubleArray(kMax); var sum = 0.0
        for (k in 1..kMax) { val a = abs(sin(PI * k * 0.12)) / k.toDouble().pow(pe); amps[k - 1] = a; sum += a }
        val rel = 0.07 + 0.26 * (1.0 - key / 108.0)                    // damper: slower on the low strings
        val gain = (1.35 * velN.pow(1.25) * v.gain / strings).toDouble()
        val att = (0.0025 * sampleRate)
        val sr = sampleRate.toDouble()
        for (s in 0 until strings) {
            val fs = f0 * 2.0.pow(detune[s] / 1200.0)
            for (k in 1..kMax) {
                val fk = fs * k * sqrt(1.0 + b * k * k)
                if (fk > sr * 0.46) break
                val a0 = gain * amps[k - 1] / sum
                if (a0 < 3e-4) continue
                val tau = tau0 / (1.0 + 0.55 * (k - 1))
                val t1 = tau * 0.16; val t2 = tau          // fast first decay, long tail
                val stop = min(v.endFrame, v.startFrame + (tau * 7.5 * sr).toInt())
                val last = min(to, stop)
                if (from >= last) continue
                val w = 2.0 * PI * fk / sr
                val t0 = (from - v.startFrame) / sr
                var phase = 2.0 * PI * fk * t0 + (v.seed * 0.618 + s * 1.7 + k * 0.9) % (2 * PI)
                var c = cos(phase); var sn = sin(phase)
                val cw = cos(w); val sw = sin(w)
                var e1 = exp(-t0 / t1); var e2 = exp(-t0 / t2)
                val d1 = exp(-1.0 / (sr * t1)); val d2 = exp(-1.0 / (sr * t2))
                var r = if (from > v.offFrame) exp(-(from - v.offFrame) / (sr * rel)) else 1.0
                val dr = exp(-1.0 / (sr * rel))
                for (i in from until last) {
                    val ai = i - v.startFrame
                    val ramp = if (ai < att) ai / att else 1.0
                    val x = a0 * (0.55 * e1 + 0.45 * e2) * sn * ramp * r
                    left[i - base] += (x * v.panL).toFloat(); right[i - base] += (x * v.panR).toFloat()
                    val nc = c * cw - sn * sw; sn = sn * cw + c * sw; c = nc
                    e1 *= d1; e2 *= d2
                    if (i >= v.offFrame) r *= dr
                }
            }
        }
        // hammer thump: a few milliseconds of filtered noise at the start of the note
        val thumpEnd = v.startFrame + (0.02 * sr).toInt()
        if (from < thumpEnd) {
            val amp = 0.05 * velN * v.gain
            for (i in from until min(to, thumpEnd)) {
                val t = (i - v.startFrame) / sr
                val nz = noise(i, v.seed) - noise(i - 1, v.seed) * 0.7
                val x = amp * nz * exp(-t / 0.006) * (if (t < 0.0008) t / 0.0008 else 1.0)
                left[i - base] += (x * v.panL).toFloat(); right[i - base] += (x * v.panR).toFloat()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // drums (General MIDI key map)

    private fun noise(i: Int, seed: Int): Double {
        var x = i * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return ((x and 0xFFFFFF) / 8388608.0) - 1.0
    }

    private fun sq(f: Double, t: Double): Double { val p = (t * f) - floor(t * f); return if (p < 0.5) 1.0 else -1.0 }
    /** First-difference high-pass (6 dB/oct tilt) so the metallic stacks are bright but not hissy. */
    private fun hp2(f: (Double) -> Double, t: Double, dt: Double) = 1.7 * (f(t) - f(t - dt))

    private val hatSet = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0)
    private val cymSet = doubleArrayOf(287.0, 421.0, 566.0, 745.0, 902.0, 1243.0)

    private fun metal(set: DoubleArray, t: Double): Double { var s = 0.0; for (f in set) s += sq(f * 3.3, t); return s / set.size }

    private fun drum(v: Voice, base: Int, n: Int, left: FloatArray, right: FloatArray) {
        val from = max(base, v.startFrame); val to = min(base + n, v.endFrame)
        if (from >= to) return
        val a = (0.6 * (v.vel / 127.0).pow(0.9) * v.gain).toDouble()
        val sr = sampleRate.toDouble(); val dt = 1.0 / sr
        for (i in from until to) {
            val t = (i - v.startFrame) / sr
            val x = a * drumSample(v.key, t, i, v.seed, dt)
            if (x == 0.0) continue
            left[i - base] += (x * v.panL).toFloat(); right[i - base] += (x * v.panR).toFloat()
        }
    }

    private fun sweep(fEnd: Double, fStart: Double, tau: Double, t: Double) = sin(2.0 * PI * (fEnd * t + (fStart - fEnd) * tau * (1.0 - exp(-t / tau))))

    private fun drumSample(key: Int, t: Double, i: Int, seed: Int, dt: Double): Double {
        fun nz(k: Int = i) = noise(k, seed)
        fun nhp(): Double = nz() - nz(i - 1)
        fun nlp(w: Int): Double { var a = 0.0; for (j in 0 until w) a += nz(i - j); return a / w }   // box low-pass, about sr/(2w)
        fun nbp(): Double = nlp(3) - 0.9 * nlp(14)
        return when (key) {
            35, 36 -> if (t > 0.45) 0.0 else tanh(2.4 * sweep(46.0, 150.0, 0.032, t) * exp(-t / 0.14)) * 0.8 + nlp(4) * 0.4 * exp(-t / 0.004)
            37 -> if (t > 0.08) 0.0 else sin(2 * PI * 1700 * t) * exp(-t / 0.012) * 0.5 + nhp() * 0.5 * exp(-t / 0.008)
            38, 40 -> if (t > 0.4) 0.0 else (sin(2 * PI * 185 * t) * exp(-t / 0.05) * 0.5 + sin(2 * PI * 330 * t) * exp(-t / 0.03) * 0.18) + (nlp(3) * 0.8 + nhp() * 0.12) * 0.7 * exp(-t / 0.085)
            39 -> if (t > 0.35) 0.0 else {
                var e = 0.0
                for (ts in doubleArrayOf(0.0, 0.011, 0.022)) if (t >= ts) e += exp(-(t - ts) / 0.0055) * 0.5
                if (t >= 0.022) e += exp(-(t - 0.022) / 0.07) * 0.55
                nbp() * 1.6 * e
            }
            41, 43, 45, 47, 48, 50 -> {
                val f = when (key) { 41 -> 82.0; 43 -> 98.0; 45 -> 116.0; 47 -> 138.0; 48 -> 165.0; else -> 196.0 }
                if (t > 0.6) 0.0 else tanh(2.0 * sweep(f, f * 1.9, 0.04, t) * exp(-t / 0.17)) * 0.8 + nlp(4) * 0.18 * exp(-t / 0.005)
            }
            42 -> if (t > 0.14) 0.0 else hp2({ metal(hatSet, it) }, t, dt) * 0.38 * exp(-t / 0.022)
            44 -> if (t > 0.2) 0.0 else hp2({ metal(hatSet, it) }, t, dt) * 0.34 * exp(-t / 0.045)
            46 -> if (t > 0.9) 0.0 else hp2({ metal(hatSet, it) }, t, dt) * 0.32 * exp(-t / 0.22)
            49, 57 -> if (t > 2.2) 0.0 else (hp2({ metal(cymSet, it) }, t, dt) * 0.3 + nhp() * 0.25) * exp(-t / 0.55)
            52 -> if (t > 1.6) 0.0 else (hp2({ metal(cymSet, it * 1.18) }, t, dt) * 0.3 + nhp() * 0.3) * exp(-t / 0.4)
            55 -> if (t > 0.9) 0.0 else (hp2({ metal(cymSet, it * 1.1) }, t, dt) * 0.3 + nhp() * 0.25) * exp(-t / 0.18)
            51, 59 -> if (t > 1.6) 0.0 else hp2({ metal(cymSet, it * 0.9) }, t, dt) * 0.22 * exp(-t / 0.4) + sin(2 * PI * 3300 * t) * 0.18 * exp(-t / 0.07)
            53 -> if (t > 1.2) 0.0 else sin(2 * PI * 2900 * t) * 0.35 * exp(-t / 0.28) + sin(2 * PI * 4400 * t) * 0.2 * exp(-t / 0.2)
            54 -> if (t > 0.3) 0.0 else (hp2({ metal(hatSet, it * 1.3) }, t, dt) * 0.3 + nhp() * 0.3) * exp(-t / 0.07)
            56 -> if (t > 0.5) 0.0 else (sq(587.0, t) + sq(845.0, t)) * 0.28 * exp(-t / 0.11)
            60, 61 -> { val f = if (key == 60) 420.0 else 330.0; if (t > 0.3) 0.0 else sweep(f, f * 1.25, 0.012, t) * exp(-t / 0.08) * 0.8 }
            62, 63, 64 -> { val f = when (key) { 62 -> 330.0; 63 -> 250.0; else -> 190.0 }; if (t > 0.5) 0.0 else sweep(f, f * 1.2, 0.02, t) * exp(-t / 0.13) * 0.8 }
            65, 66 -> { val f = if (key == 65) 520.0 else 380.0; if (t > 0.4) 0.0 else sin(2 * PI * f * t) * exp(-t / 0.09) * 0.6 + nhp() * 0.2 * exp(-t / 0.01) }
            67, 68 -> if (t > 0.5) 0.0 else (sin(2 * PI * (if (key == 67) 900.0 else 700.0) * t) + 0.6 * sin(2 * PI * (if (key == 67) 1350.0 else 1050.0) * t)) * 0.3 * exp(-t / 0.1)
            69, 70 -> if (t > 0.18) 0.0 else (nlp(2) - 0.8 * nlp(8)) * 1.3 * (if (t < 0.006) t / 0.006 else 1.0) * exp(-t / 0.035)
            75 -> if (t > 0.1) 0.0 else sin(2 * PI * 2500 * t) * 0.6 * exp(-t / 0.018)
            76, 77 -> if (t > 0.15) 0.0 else sin(2 * PI * (if (key == 76) 1300.0 else 900.0) * t) * 0.6 * exp(-t / 0.03)
            80 -> if (t > 0.35) 0.0 else (sin(2 * PI * 6300 * t) + 0.5 * sin(2 * PI * 9400 * t)) * 0.3 * exp(-t / 0.1)
            81 -> if (t > 1.8) 0.0 else (sin(2 * PI * 6300 * t) + 0.5 * sin(2 * PI * 9400 * t)) * 0.28 * exp(-t / 0.7)
            else -> if (t > 0.08) 0.0 else nhp() * 0.4 * exp(-t / 0.02)
        }
    }
}
