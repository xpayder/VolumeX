package app.feldkit.offload

import app.feldkit.hash.HashAlgo
import app.feldkit.hash.MultiHasher
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class DestStatus { COPIED, VERIFIED, MISMATCH, ERROR }

class DestResult(val destination: String, val status: DestStatus, val writtenName: String, val message: String? = null)

/** One file of the job: what it hashed to on the source and how each destination fared. */
class FileResult(
    val relPath: List<String>,
    val size: Long,
    /** Hash of the bytes as they came off the source, per algorithm (hex / C4 id); null when the source could not be read. */
    val hashes: Map<HashAlgo, String>?,
    val perDest: List<DestResult>,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && perDest.all { it.status == DestStatus.VERIFIED || it.status == DestStatus.COPIED }
    val path: String get() = relPath.joinToString("/")
}

class OffloadConfig(
    val algos: List<HashAlgo> = listOf(HashAlgo.XXH64),
    /** Read every copy back from its destination and compare with the source hash. */
    val verify: Boolean = true,
    /** Folder created inside each destination to hold the job's files; null or empty puts the items straight into the destination. */
    val jobFolder: String? = null,
    val chunkBytes: Int = 1 shl 20,
)

/** Live view of a running job. All fields are read from other threads. */
class OffloadProgress {
    @Volatile var totalBytes = 0L
    @Volatile var doneBytes = 0L            // source bytes read, across files
    @Volatile var verifiedBytes = 0L
    @Volatile var fileCount = 0
    @Volatile var fileIndex = 0
    @Volatile var currentFile = ""
    @Volatile var phase = "Preparing"
    @Volatile var finished = false
    @Volatile var startedNs = System.nanoTime()
    val results: MutableList<FileResult> = java.util.Collections.synchronizedList(ArrayList<FileResult>())
}

/**
 * Reads each file from the source exactly once, hashes it on the fly and writes it to every destination in parallel
 * (one writer thread and a bounded queue per destination), then reads each copy back and compares hashes.
 */
class OffloadEngine(
    private val source: FileSystemReader,
    private val volume: Int,
    private val dests: List<Destination>,
    private val cfg: OffloadConfig,
) {
    private val cancelled = AtomicBoolean(false)
    val progress = OffloadProgress()
    fun cancel() { cancelled.set(true) }

    private class Item(val entry: FileSystemEntry, val rel: List<String>)

    private fun expand(items: List<FileSystemEntry>): List<Item> {
        val out = ArrayList<Item>()
        fun walk(e: FileSystemEntry, rel: List<String>) {
            if (e.isDirectory) source.listDirectory(volume, e.path).sortedBy { it.name }.forEach { walk(it, rel + e.name) }
            else out.add(Item(e, rel + e.name))
        }
        for (e in items) walk(e, emptyList())
        return out
    }

    /** Runs the whole job on the calling thread. Returns the per-file results (also available live in [progress]). */
    fun run(items: List<FileSystemEntry>): List<FileResult> {
        val files = expand(items)
        progress.fileCount = files.size
        progress.totalBytes = files.sumOf { it.entry.size }
        progress.startedNs = System.nanoTime()
        for ((i, f) in files.withIndex()) {
            if (cancelled.get()) break
            progress.fileIndex = i + 1; progress.currentFile = f.rel.last()
            progress.results.add(copyOne(f))
        }
        progress.phase = if (cancelled.get()) "Cancelled" else "Done"
        progress.finished = true
        return progress.results.toList()
    }

    private class Writer(val dest: Destination, val sink: DestSink?, openError: String?) {
        val queue = ArrayBlockingQueue<ByteArray>(8)
        @Volatile var error: String? = openError
        var thread: Thread? = null
    }

    private val end = ByteArray(0)

    private fun copyOne(f: Item): FileResult {
        val parent = (if (cfg.jobFolder.isNullOrEmpty()) emptyList() else listOf(cfg.jobFolder)) + f.rel.dropLast(1)
        val name = f.rel.last()
        progress.phase = "Copying"
        val writers = dests.map { d ->
            try { Writer(d, d.dir(parent).create(name), null) } catch (e: Exception) { Writer(d, null, e.message ?: "cannot create file") }
        }
        for (w in writers) {
            val sink = w.sink ?: continue
            w.thread = Thread {
                try {
                    while (true) {
                        val b = w.queue.take()
                        if (b === end) break
                        if (w.error == null) try { sink.write(b, 0, b.size) } catch (e: Exception) { w.error = e.message ?: "write failed" }
                    }
                } catch (_: InterruptedException) {}
            }.also { it.isDaemon = true; it.start() }
        }
        val hasher = MultiHasher(cfg.algos)
        var readError: String? = null
        val base = progress.doneBytes
        try {
            val stream = object : java.io.OutputStream() {
                override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (cancelled.get()) throw IOException("cancelled")
                    hasher.update(b, off, len)
                    val copy = b.copyOfRange(off, off + len)
                    for (w in writers) if (w.sink != null) w.queue.put(copy)       // shared read-only; the writers never modify it
                }
            }
            val ok = source.readFileTo(f.entry, stream) { n -> progress.doneBytes = base + n }
            if (!ok) readError = "Could not read the file from the drive"
        } catch (e: Exception) { readError = if (cancelled.get()) "cancelled" else (e.message ?: "read failed") }
        for (w in writers) if (w.sink != null) w.queue.put(end)
        for (w in writers) w.thread?.join()
        progress.doneBytes = base + f.entry.size

        if (readError != null) {
            hasher.finish()
            for (w in writers) try { w.sink?.abort() } catch (_: Exception) {}
            return FileResult(f.rel, f.entry.size, null, writers.map { DestResult(it.dest.label, DestStatus.ERROR, name, readError) }, readError)
        }
        val hashes = hasher.finishHex()
        for (w in writers) {
            val sink = w.sink ?: continue
            try { if (w.error == null) sink.finish() else sink.abort() } catch (e: Exception) { w.error = e.message ?: "sync failed" }
        }
        val results = arrayOfNulls<DestResult>(writers.size)
        val verifyThreads = ArrayList<Thread>()
        progress.phase = if (cfg.verify) "Verifying" else "Copying"
        for ((i, w) in writers.withIndex()) {
            val sink = w.sink
            if (sink == null || w.error != null) { results[i] = DestResult(w.dest.label, DestStatus.ERROR, name, w.error ?: "could not write"); continue }
            if (!cfg.verify) { results[i] = DestResult(w.dest.label, DestStatus.COPIED, sink.name); continue }
            // read each copy back, in parallel, the way an independent reader would see it
            verifyThreads.add(Thread {
                results[i] = try {
                    val dir = w.dest.dir(parent)
                    val ins = dir.open(sink.name) ?: throw IOException("copy not found after writing")
                    val mh = MultiHasher(cfg.algos)
                    ins.use { s -> val buf = ByteArray(cfg.chunkBytes); while (true) { val n = s.read(buf); if (n <= 0) break; mh.update(buf, 0, n) } }
                    if (mh.finishHex() == hashes) DestResult(w.dest.label, DestStatus.VERIFIED, sink.name)
                    else { try { dir.delete(sink.name) } catch (_: Exception) {}; DestResult(w.dest.label, DestStatus.MISMATCH, sink.name, "Checksum differs after writing; the bad copy was removed") }
                } catch (e: Exception) { DestResult(w.dest.label, DestStatus.ERROR, sink.name, e.message ?: "verification failed") }
            }.also { it.isDaemon = true; it.start() })
        }
        verifyThreads.forEach { it.join() }
        progress.verifiedBytes += f.entry.size
        return FileResult(f.rel, f.entry.size, hashes, results.map { it!! })
    }
}
