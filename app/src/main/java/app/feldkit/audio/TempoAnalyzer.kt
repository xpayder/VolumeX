package app.feldkit.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tempo detection for finished recordings.
 *
 * 1. A band-wise, log-compressed spectral-flux "onset strength" envelope is computed at ~5.8 ms resolution.
 * 2. Its Fourier spectrum is read as a bank of combs (the fundamental plus its harmonics): the tempo is the frequency
 *    whose comb collects the most energy, weighted by how plausible the tempo is as a musical pulse.
 * 3. The estimate is refined on a very fine grid (0.0005 BPM) with a phasor recurrence over the whole analysis window,
 *    then parabolically interpolated. Over a minute or more of a steady groove this resolves hundredths of a BPM.
 * 4. The same refinement is repeated on three independent slices of the track; if they disagree the tempo is reported
 *    as varying (the number is then an average, not a grid).
 *
 * Returns null when there is no pulse to speak of (ambient, free rhythm, speech) instead of inventing a number.
 */
object TempoAnalyzer {
    data class Result(
        val bpm: Double,
        /** 0..1: how clearly the pulse stands out of the spectrum. */
        val confidence: Double,
        /** True when three separate slices of the track agree to within a few hundredths of a BPM. */
        val steady: Boolean,
        /** Largest difference between the slices' estimates, in BPM. */
        val spread: Double,
        /** The other readings of the same pulse (half / double time) that fall in a usable range. */
        val alternates: List<Double>,
        /** Beats in the file when its length is a whole number of bars at this tempo (a loop), else 0. */
        val loopBeats: Int = 0,
    )

    /** Set in tests to print the quantities the decisions are based on. */
    var debug = false

    /** Ratios between the detected periodicity and the tempo it may stand for: octaves, and 3:2 / 4:3 (dotted subdivisions). */
    private val MULTS = doubleArrayOf(0.5, 2.0 / 3.0, 0.75, 1.0, 4.0 / 3.0, 1.5, 2.0)

    private const val TARGET_RATE = 22050
    private const val WIN = 1024
    private const val HOP = 128
    private val HARMONICS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)
    private val WEIGHTS = doubleArrayOf(1.0, 1.0, 0.7, 0.6, 0.4, 0.3)
    private const val MIN_BPM = 45.0
    private const val MAX_BPM = 240.0

    /** One reading of the pulse with the evidence for and against it. */
    class Candidate(
        val bpm: Double,
        /** Strength of the periodicity at exactly this tempo (comb of the onset spectrum). */
        val comb: Double,
        /** >1 when every other beat at this tempo is accented, i.e. the felt pulse is slower. */
        val contrast: Double,
        /** True when it comes from a ratio other than a plain octave (3:2, 4:3...). */
        val nonOctave: Boolean,
        /** True when a whole, bar-aligned number of beats fits the file length at this tempo (loops). */
        val loopFit: Boolean,
        /** Beats in the file at this tempo, when it is a loop fit. */
        val beats: Int,
        /** Came from a spectral peak (as opposed to only from the loop-length grid). */
        val fromPeak: Boolean,
        /** Mean onset strength on this tempo's beat grid starting at t = 0 (a loop starts on its first beat), over the mean overall. */
        val gridAtZero: Double,
        /** Same with the best-fitting phase. */
        val gridBest: Double,
        /** Onset strength half-way between beats relative to on the beats. */
        val offbeat: Double,
        /** Comb strength at half / quarter / double of this tempo relative to this tempo. */
        val subHalf: Double,
        val subQuarter: Double,
        val dbl: Double,
    )

    /** Everything the decision is based on; exposed so the rule can be tuned against labelled material. */
    class Features(val seconds: Double, val prominence: Double, val crest: Double, val candidates: List<Candidate>, val env: DoubleArray, val hopSec: Double)

    fun analyze(pcm: FloatArray, sampleRate: Int, maxSeconds: Int = 180): Result? {
        val f = features(pcm, sampleRate, maxSeconds) ?: return null
        return decide(f)
    }

    fun features(pcm: FloatArray, sampleRate: Int, maxSeconds: Int = 180): Features? {
        if (pcm.isEmpty() || sampleRate < 8000) return null
        val total = pcm.size.toDouble() / sampleRate
        if (total < 3.0) return null
        // skip the first seconds (fade-ins, intros without a groove) on long tracks, keep a bounded window
        val start = if (total > maxSeconds + 20) (min(20.0, total * 0.08) * sampleRate).toInt() else 0
        val end = min(pcm.size, start + maxSeconds * sampleRate)
        val mono = decimate(pcm, start, end, sampleRate)
        val env = onsetEnvelope(mono) ?: return null
        val hopSec = HOP.toDouble() / TARGET_RATE
        return buildFeatures(env, hopSec, total)
    }

    private const val LOOP_MAX_SECONDS = 40.0

    /** Tolerance for "the file length is a whole number of beats at this tempo". */
    private fun lockTolerance(seconds: Double, bpm: Double) = max(0.06 + 0.8 / seconds, LOCK_REL * bpm)
    private const val LOCK_REL = 0.01

    private fun buildFeatures(env: DoubleArray, hopSec: Double, fileSeconds: Double): Features? {
        val spec = spectrum(env, hopSec)
        val step = 0.05
        val xs = ArrayList<Double>(); val raw = ArrayList<Double>()
        var bpm = MIN_BPM
        while (bpm <= MAX_BPM) { xs.add(bpm); raw.add(comb(spec, bpm / 60.0)); bpm += step }
        val median = raw.sorted()[raw.size / 2].coerceAtLeast(1e-12)
        val scores = DoubleArray(xs.size) { raw[it] * Math.sqrt(prior(xs[it])) }
        // the strongest spectral peaks, well separated, each is a base for the ratio hypotheses
        val peaks = ArrayList<Int>()
        val order = (1 until scores.size - 1).filter { scores[it] >= scores[it - 1] && scores[it] > scores[it + 1] }.sortedByDescending { scores[it] }
        for (i in order) { if (peaks.size >= 3) break; if (scores[i] < scores[order[0]] * 0.4) break; if (peaks.none { abs(xs[it] - xs[i]) / xs[i] < 0.04 }) peaks.add(i) }
        if (peaks.isEmpty()) return null
        val prominence = raw[peaks[0]] / median
        val crest = crest(env)

        val seen = ArrayList<Candidate>()
        fun add(b: Double, nonOct: Boolean, fromPeak: Boolean) {
            if (b < 55.0 || b > 215.0) return
            if (seen.any { abs(it.bpm - b) / b < 0.003 }) return
            val k = Math.round(b * fileSeconds / 60.0).toInt()
            val l = if (k >= 2 && fileSeconds <= LOOP_MAX_SECONDS) 60.0 * k / fileSeconds else Double.NaN
            val fit = !l.isNaN() && abs(l - b) <= lockTolerance(fileSeconds, b) && k % 2 == 0
            val bb = if (fit) l else b
            val f = bb / 60.0
            val cb = comb(spec, f).coerceAtLeast(1e-12)
            val g = gridStats(env, hopSec, f)
            seen.add(Candidate(bb, cb, accentContrast(env, hopSec, f), nonOct, fit, if (fit) k else 0, fromPeak, g[0], g[1], g[2],
                comb(spec, f / 2) / cb, comb(spec, f / 4) / cb, comb(spec, f * 2) / cb))
        }
        for (pi in peaks) {
            val b0 = parabolic(xs, raw, pi)
            for (m in MULTS) add(b0 * m, m != 0.5 && m != 1.0 && m != 2.0, true)
        }
        if (fileSeconds <= LOOP_MAX_SECONDS) {
            var k = 2
            while (60.0 * k / fileSeconds <= 215.0) { val l = 60.0 * k / fileSeconds; if (l >= 55.0 && k % 2 == 0) add(l, false, false); k++ }
        }
        return Features(fileSeconds, prominence, crest, seen, env, hopSec)
    }

    /** [grid at t = 0, grid at the best phase, off-beat ratio], all relative to the mean envelope. */
    private fun gridStats(env: DoubleArray, hopSec: Double, fHz: Double): DoubleArray {
        val mean = env.average().coerceAtLeast(1e-12)
        fun sampleAt(t: Double): Double { val c = (t / hopSec).roundToInt(); var m = 0.0; for (d in -1..1) if (c + d in env.indices) m = max(m, env[c + d]); return m }
        val period = 1.0 / fHz
        val total = env.size * hopSec
        fun gridMean(phase: Double): Double { var s = 0.0; var n = 0; var t = phase; while (t < total - hopSec * 2) { s += sampleAt(t); n++; t += period }; return if (n == 0) 0.0 else s / n }
        fun offMean(phase: Double): Double { var s = 0.0; var n = 0; var t = phase + period / 2; while (t < total - hopSec * 2) { s += sampleAt(t); n++; t += period }; return if (n == 0) 0.0 else s / n }
        var zr = 0.0; var zi = 0.0
        val w = 2.0 * PI * fHz * hopSec
        for (i in env.indices) { zr += env[i] * cos(w * i); zi -= env[i] * sin(w * i) }
        val theta = ((Math.atan2(zi, zr) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
        val bestPhase = theta / fHz
        val g0 = gridMean(0.0); val gb = gridMean(bestPhase)
        return doubleArrayOf(g0 / mean, gb / mean, offMean(bestPhase) / max(1e-12, gb))
    }

    private fun parabolic(xs: List<Double>, ys: List<Double>, i: Int): Double {
        if (i <= 0 || i >= xs.size - 1) return xs[i]
        val a = ys[i - 1]; val b = ys[i]; val c = ys[i + 1]; val den = a - 2 * b + c
        return if (den < 0) xs[i] + 0.5 * (a - c) / den * (xs[1] - xs[0]) else xs[i]
    }

    /** Evidence vector of a candidate, in the order of [TempoModel.WEIGHTS]. */
    internal fun evidence(c: Candidate, maxComb: Double): DoubleArray {
        val bars = if (c.loopFit && c.beats % 4 == 0) c.beats / 4 else 0
        val log2b = ln(c.bpm / 120.0) / ln(2.0)
        return doubleArrayOf(
            ln(c.comb / maxComb + 1e-9), ln(TempoModel.prior(c.bpm) + 1e-6), ln(max(c.contrast, 1.0)),
            if (c.nonOctave) 1.0 else 0.0, if (c.loopFit) 1.0 else 0.0, if (c.loopFit && c.beats % 4 == 0) 1.0 else 0.0,
            if (bars > 0 && bars and (bars - 1) == 0) 1.0 else 0.0, if (c.fromPeak) 1.0 else 0.0,
            ln(max(c.gridAtZero, 1e-3)), ln(max(c.gridBest, 1e-3)), ln(max(c.offbeat, 1e-3)),
            ln(max(c.subHalf, 1e-4)), ln(max(c.subQuarter, 1e-4)), ln(max(c.dbl, 1e-4)), log2b * log2b,
        )
    }

    internal fun score(c: Candidate, maxComb: Double): Double {
        val x = evidence(c, maxComb); var s = 0.0
        for (i in x.indices) s += TempoModel.WEIGHTS[i] * x[i]
        return s
    }

    private fun decide(f: Features): Result? {
        val gateStrict = f.prominence >= 2.2 && f.crest >= 8.0
        val gateLoose = f.prominence >= 1.5 && f.crest >= 4.0
        if (!gateLoose || f.candidates.isEmpty()) return null
        val maxComb = f.candidates.maxOf { it.comb }.coerceAtLeast(1e-12)
        val best = f.candidates.maxByOrNull { score(it, maxComb) } ?: return null
        // a weak pulse is only believed when the file length independently agrees with it
        if (!gateStrict && !best.loopFit) return null
        var bpm = best.bpm
        // precise value: tight search around the choice (a loop that fits the length keeps its exact grid value)
        if (!best.loopFit) bpm = refine(f.env, f.hopSec, bpm / 60.0, 0.006) * 60.0
        // independent slices of the track for the steadiness check
        val sliceCount = min(3, (f.env.size * f.hopSec / 12.0).toInt())
        val parts = ArrayList<Double>()
        if (sliceCount >= 2) {
            val len = f.env.size / sliceCount
            for (k in 0 until sliceCount) parts.add(refine(f.env.copyOfRange(k * len, (k + 1) * len), f.hopSec, bpm / 60.0, 0.004) * 60.0)
        }
        val segSec = if (sliceCount >= 2) f.env.size * f.hopSec / sliceCount else 0.0
        val spread = if (parts.isEmpty()) 0.0 else (parts.max() - parts.min())
        val steady = parts.isNotEmpty() && spread <= 0.06 + 1.2 / segSec
        val alts = ArrayList<Double>()
        for (m in doubleArrayOf(0.5, 2.0)) { val a = bpm * m; if (a in 55.0..220.0) alts.add(a) }
        val conf = (1.0 - exp(-(f.prominence - 1.0) / 4.0)).coerceIn(0.0, 1.0)
        return Result(snap(bpm), conf, steady, spread, alts.map { snap(it) }, if (best.loopFit) best.beats else 0)
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Integer decimation to roughly 22 kHz with a cascaded box low-pass (enough for an onset envelope). */
    private fun decimate(x: FloatArray, from: Int, to: Int, rate: Int): FloatArray {
        val factor = max(1, (rate.toDouble() / TARGET_RATE).roundToInt())
        if (factor == 1) return FloatArray(to - from) { x[from + it] }
        val n = (to - from) / factor
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0.0
            val base = from + i * factor
            for (k in 0 until factor) s += x[base + k]
            out[i] = (s / factor).toFloat()
        }
        return out
    }

    private fun onsetEnvelope(x: FloatArray): DoubleArray? {
        val frames = (x.size - WIN) / HOP
        if (frames < 400) return null
        val fft = Fft(WIN)
        val window = DoubleArray(WIN) { 0.5 - 0.5 * cos(2.0 * PI * it / WIN) }
        val wsum = window.sum()
        // log-spaced bands between 40 Hz and 9 kHz
        val nb = 28
        val edges = IntArray(nb + 1) { b ->
            val f = 40.0 * Math.pow(9000.0 / 40.0, b.toDouble() / nb)
            min(WIN / 2 - 1, max(1, (f * WIN / TARGET_RATE).roundToInt()))
        }
        for (b in 1..nb) if (edges[b] <= edges[b - 1]) edges[b] = edges[b - 1] + 1
        val re = DoubleArray(WIN); val im = DoubleArray(WIN)
        val prev = DoubleArray(nb)
        val cur = DoubleArray(nb)
        val flux = DoubleArray(frames)
        for (t in 0 until frames) {
            val off = t * HOP
            for (i in 0 until WIN) { re[i] = x[off + i] * window[i]; im[i] = 0.0 }
            fft.transform(re, im)
            for (b in 0 until nb) {
                var e = 0.0
                for (k in edges[b] until min(edges[b + 1], WIN / 2)) e += re[k] * re[k] + im[k] * im[k]
                val amp = 2.0 * sqrt(e / (edges[b + 1] - edges[b])) / wsum
                cur[b] = ln(1.0 + 1000.0 * amp)
            }
            if (t > 0) { var s = 0.0; for (b in 0 until nb) { val d = cur[b] - prev[b]; if (d > 0) s += d }; flux[t] = s }
            System.arraycopy(cur, 0, prev, 0, nb)
        }
        // remove the slow trend (about one second) and rectify, then scale
        val w = (1.0 / (HOP.toDouble() / TARGET_RATE)).roundToInt()
        val out = DoubleArray(frames)
        var acc = 0.0; val half = w / 2
        val prefix = DoubleArray(frames + 1); for (i in 0 until frames) prefix[i + 1] = prefix[i] + flux[i]
        for (i in 0 until frames) {
            val a = max(0, i - half); val b = min(frames, i + half + 1)
            val mean = (prefix[b] - prefix[a]) / (b - a)
            out[i] = max(0.0, flux[i] - mean)
            acc += out[i]
        }
        if (acc <= 1e-9) return null
        return out
    }

    // ------------------------------------------------------------------------------------------------------------

    private class Spectrum(val mag: DoubleArray, val binHz: Double)

    private fun spectrum(env: DoubleArray, hopSec: Double): Spectrum {
        val n = env.size
        val m = Fft.nextPow2(n * 4)
        val fft = Fft(m)
        val re = DoubleArray(m); val im = DoubleArray(m)
        var mean = 0.0; for (v in env) mean += v; mean /= n
        for (i in 0 until n) { val w = 0.5 - 0.5 * cos(2.0 * PI * i / n); re[i] = (env[i] - mean) * w }
        fft.transform(re, im)
        val mag = DoubleArray(m / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        return Spectrum(mag, 1.0 / (m * hopSec))
    }

    private fun Spectrum.at(f: Double): Double {
        val p = f / binHz
        val i = p.toInt()
        if (i < 0 || i + 1 >= mag.size) return 0.0
        val fr = p - i
        return mag[i] * (1 - fr) + mag[i + 1] * fr
    }

    private fun comb(s: Spectrum, f: Double): Double {
        var c = 0.0; var ws = 0.0
        for (k in HARMONICS.indices) { c += WEIGHTS[k] * s.at(f * HARMONICS[k]); ws += WEIGHTS[k] }
        return c / ws
    }

    /** How musically likely a pulse is: flat across the common range, falling off outside it. */
    private fun prior(bpm: Double): Double {
        val lo = 80.0; val hi = 165.0
        val d = if (bpm < lo) ln(lo / bpm) / ln(2.0) else if (bpm > hi) ln(bpm / hi) / ln(2.0) else 0.0
        return exp(-0.5 * (d / 0.45) * (d / 0.45))
    }

    /**
     * Ratio between the stronger and the weaker of two alternating beat classes on the grid of [fHz]. A value well above 1
     * means every other grid point is an accent, i.e. the felt pulse is half as fast and [fHz] is its subdivision.
     */
    private fun accentContrast(env: DoubleArray, hopSec: Double, fHz: Double): Double {
        var zr = 0.0; var zi = 0.0
        val w = 2.0 * PI * fHz * hopSec
        for (i in env.indices) { zr += env[i] * cos(w * i); zi -= env[i] * sin(w * i) }
        val theta = Math.atan2(zi, zr) / (2.0 * PI)          // beat k sits at (theta + k) / fHz
        var even = 0.0; var odd = 0.0; var ne = 0; var no = 0
        var k = 0
        while (true) {
            val t = (((theta % 1.0) + 1.0) % 1.0 + k) / fHz
            val c = (t / hopSec).roundToInt()
            if (c + 2 >= env.size) break
            var m = 0.0
            for (d in -2..2) if (c + d >= 0) m = max(m, env[c + d])
            if (k % 2 == 0) { even += m; ne++ } else { odd += m; no++ }
            k++
        }
        if (ne == 0 || no == 0) return 1.0
        val a = even / ne; val b = odd / no
        return max(a, b) / max(1e-9, min(a, b))
    }

    /** Mean of the strongest 5 % of frames over the mean of all frames: high for hits, low for noise-like flux. */
    private fun crest(env: DoubleArray): Double {
        val s = env.sortedDescending(); val k = max(1, s.size / 20)
        val top = s.take(k).average(); val all = env.average().coerceAtLeast(1e-12)
        return top / all
    }

    /** Rounds to hundredths, and onto the whole number when the estimate is within analysis noise of it. */
    private fun snap(b: Double): Double {
        val r = b.roundToInt().toDouble()
        return if (abs(b - r) <= 0.015) r else Math.round(b * 100.0) / 100.0
    }

    /** Fine search for the comb's peak frequency (Hz) near [f0], ±[rel] relative, returning the interpolated maximum. */
    private fun refine(env: DoubleArray, hopSec: Double, f0: Double, rel: Double): Double {
        val n = env.size
        var mean = 0.0; for (v in env) mean += v; mean /= n
        val win = DoubleArray(n) { (env[it] - mean) * (0.5 - 0.5 * cos(2.0 * PI * it / n)) }
        val stepRel = 0.00005
        val count = (2 * rel / stepRel).toInt() + 1
        val vals = DoubleArray(count)
        for (g in 0 until count) {
            val f = f0 * (1 - rel + g * stepRel)
            var total = 0.0
            for (k in HARMONICS.indices) {
                val fh = f * HARMONICS[k]
                val w = 2.0 * PI * fh * hopSec
                val wr = cos(w); val wi = -sin(w)
                var zr = 1.0; var zi = 0.0; var sr = 0.0; var si = 0.0
                for (i in 0 until n) {
                    sr += win[i] * zr; si += win[i] * zi
                    val nr = zr * wr - zi * wi; zi = zr * wi + zi * wr; zr = nr
                    if ((i and 4095) == 4095) { val m = 1.0 / sqrt(zr * zr + zi * zi); zr *= m; zi *= m }
                }
                total += WEIGHTS[k] * sqrt(sr * sr + si * si)
            }
            vals[g] = total
        }
        var bi = 0; for (i in 1 until count) if (vals[i] > vals[bi]) bi = i
        var f = f0 * (1 - rel + bi * stepRel)
        if (bi in 1 until count - 1) {
            val a = vals[bi - 1]; val b = vals[bi]; val c = vals[bi + 1]
            val den = a - 2 * b + c
            if (den < 0) f += 0.5 * (a - c) / den * f0 * stepRel
        }
        return f
    }
}
