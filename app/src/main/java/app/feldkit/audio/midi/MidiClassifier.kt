package app.feldkit.audio.midi

/**
 * Decides per channel whether a part is drums. The General MIDI rule is channel 10, but clips exported from a DAW often put a
 * drum pattern on channel 1 with no program change, so the notes themselves are looked at too: a handful of kit pitches,
 * short one-shot lengths, and the usual kick / snare / hat trio.
 */
object MidiClassifier {
    private val kit = setOf(35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 59, 69, 70, 75, 76, 77, 80, 81)
    private val drumWords = Regex("drum|kit|perc|beat|kick|snare|hat|hihat|808|cymbal|tom(?![a-z])|shaker|clap", RegexOption.IGNORE_CASE)
    private val toneWords = Regex("bass|lead|piano|keys|key|synth|chord|melod|pad|arp|pluck|string|guitar|vocal|organ|brass|bell|sub", RegexOption.IGNORE_CASE)

    /** [remap]: channel -> GM drum key, for files that hold a pattern for one drum on ordinary pitches (named "Hi Hat 12", "Kick 03" ...). */
    class Verdict(val drums: Set<Int>, val reason: Map<Int, String>, val remap: Map<Int, Int> = emptyMap())

    private class Named(val regex: Regex, val key: Int)
    private val single = listOf(
        Named(Regex("open ?hat|hat ?open|hihat ?open|open ?hi-?hat", RegexOption.IGNORE_CASE), 46),
        Named(Regex("hi-?hat|(?<![a-z])hats?(?![a-z])", RegexOption.IGNORE_CASE), 42),
        Named(Regex("kick|(?<![a-z])bd(?![a-z])|bass drum", RegexOption.IGNORE_CASE), 36),
        Named(Regex("snare|(?<![a-z])sd(?![a-z])|(?<![a-z])sn(?![a-z])", RegexOption.IGNORE_CASE), 38),
        Named(Regex("clap", RegexOption.IGNORE_CASE), 39),
        Named(Regex("rim ?shot|side ?stick|(?<![a-z])rim(?![a-z])", RegexOption.IGNORE_CASE), 37),
        Named(Regex("(?<![a-z])toms?(?![a-z])", RegexOption.IGNORE_CASE), 45),
        Named(Regex("shaker|maracas", RegexOption.IGNORE_CASE), 70),
        Named(Regex("crash|cymbal", RegexOption.IGNORE_CASE), 49),
        Named(Regex("(?<![a-z])ride(?![a-z])", RegexOption.IGNORE_CASE), 51),
        Named(Regex("(?<![a-z])perc|percussion|tamb", RegexOption.IGNORE_CASE), 54),
    )

    /** [hint]: the file's own name plus its folder, which is how sample packs say what a MIDI file is. */
    fun classify(song: Song, hint: String = ""): Verdict {
        val byChannel = song.notes.groupBy { it.channel }
        val drums = HashSet<Int>(); val reasons = HashMap<Int, String>()
        val explicit = byChannel.containsKey(9)
        val remap = HashMap<Int, Int>()
        val named = if (hint.isBlank()) null else single.firstOrNull { it.regex.containsMatchIn(hint) }
        for ((ch, ns) in byChannel) {
            val distinctKeys = ns.map { it.key }.distinct()
            // a one-drum pattern sitting on ordinary pitches: the name says which drum it is
            if (named != null && ch != 9 && (distinctKeys.size <= 3 || (distinctKeys.size <= 6 && distinctKeys.max() - distinctKeys.min() <= 14)) && !(distinctKeys.all { it in kit } && ns.size > 3 && distinctKeys.size > 3)) {
                drums.add(ch); remap[ch] = named.key; reasons[ch] = "named pattern -> GM key ${named.key}"; continue
            }
            if (ch == 9) { drums.add(ch); reasons[ch] = "channel 10"; continue }
            val names = ns.map { it.track }.distinct().mapNotNull { song.trackNames[it] }.joinToString(" ")
            var score = 0.0
            if (drumWords.containsMatchIn(names)) score += 3.0
            if (toneWords.containsMatchIn(names)) score -= 3.0
            if (toneWords.containsMatchIn(hint)) score -= 3.0
            val hasProgram = song.programs.any { it.channel == ch && it.program != 0 }
            if (hasProgram) score -= 2.0
            val keys = ns.map { it.key }
            val inKit = keys.count { it in kit }.toDouble() / keys.size
            val distinct = keys.distinct()
            if (inKit >= 0.95) score += 1.5
            if (distinct.size <= 14) score += 0.5
            // kick + snare + hat together is the signature of a kit
            val trio = listOf(36, 38, 42).count { it in distinct } + (if (35 in distinct) 1 else 0).coerceAtMost(0)
            if (trio >= 3) score += 2.5 else if (trio == 2 && inKit >= 0.95) score += 1.0
            // one-shot lengths: each pitch always has the same short gate
            val lens = ns.map { song.seconds(it.endTick) - song.seconds(it.tick) }
            val med = lens.sorted()[lens.size / 2]
            if (med < 0.3) score += 1.0 else if (med > 0.6) score -= 1.5
            val perPitchConst = distinct.count { k ->
                val l = ns.filter { it.key == k }.map { it.endTick - it.tick }
                l.max() - l.min() <= maxOf(2L, l.average().toLong() / 8)
            }.toDouble() / distinct.size
            if (perPitchConst >= 0.8 && ns.size >= 6) score += 1.0
            // stacked thirds / fourths sounding together are chords, which no drum kit plays
            val groups = ns.groupBy { it.tick }.values.map { g -> g.map { it.key }.distinct().sorted() }
            val chordy = groups.count { g -> g.size >= 3 && g.zipWithNext { a, b -> b - a }.all { it in 3..5 } }
            if (groups.isNotEmpty() && chordy.toDouble() / groups.size >= 0.25) score -= 3.5
            // tonal material uses pitches across a range and moves in steps / chords; a kit sits low and sparse
            if (keys.any { it > 84 } && inKit < 0.9) score -= 1.0
            val threshold = if (explicit) 5.0 else 4.0     // when a real drum channel exists, other channels need more proof
            if (score >= threshold) { drums.add(ch); reasons[ch] = "kit pattern (score %.1f)".format(score) }
        }
        return Verdict(drums, reasons, remap)
    }
}
