package app.feldkit.offload

import app.feldkit.hash.HashAlgo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain checksum lists written next to the copied files, in the formats the standard command-line tools verify:
 * `xxhsum -c`, `md5sum -c` / `shasum -c`. Paths are relative to the folder the list sits in.
 */
object Sidecars {
    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())

    /** One line per verified file for [algo], or null when [algo] has no list format. */
    fun listText(algo: HashAlgo, destination: String, results: List<FileResult>, relativeTo: List<String>): String {
        val sb = StringBuilder()
        for (r in results) {
            val d = r.perDest.firstOrNull { it.destination == destination } ?: continue
            if (d.status != DestStatus.VERIFIED && d.status != DestStatus.COPIED) continue
            val h = r.hashes?.get(algo) ?: continue
            val rel = r.relPath.drop(relativeTo.size).toMutableList().also { it[it.size - 1] = d.writtenName }.joinToString("/")
            val shown = if (algo == HashAlgo.XXH3_64) "XXH3_$h" else h          // the way `xxhsum -H3` prints and reads it
            sb.append(shown).append("  ").append(rel).append('\n')
        }
        return sb.toString()
    }

    /** Writes `FeldKit <date>.<ext>` per algorithm into the job folder (or the destination root) of every destination. */
    fun write(dests: List<Destination>, jobFolder: String?, algos: List<HashAlgo>, results: List<FileResult>) {
        val folder = if (jobFolder.isNullOrEmpty()) emptyList() else listOf(jobFolder)
        val stamp = stamp()
        for (d in dests) for (a in algos) {
            val text = listText(a, d.label, results, folder)
            if (text.isEmpty()) continue
            try {
                val sink = d.dir(folder).create("FeldKit $stamp.${a.fileExt}")
                val bytes = text.toByteArray(); sink.write(bytes, 0, bytes.size); sink.finish()
            } catch (_: Exception) { /* a missing checksum list must not fail the copy itself */ }
        }
    }
}
