package com.fatalpuppet.volumex.storage.filesystem

interface FileSystemReader {
    /** Initialize and validate the filesystem. Returns true on success. */
    fun mount(): Boolean

    /** List available volumes/partitions. */
    fun getVolumeInfos(): List<VolumeInfo>

    /** List entries in the given directory path for the given volume index. */
    fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry>

    /** Read the full content of a file entry into a ByteArray. */
    fun readFile(entry: FileSystemEntry): ByteArray?

    /**
     * Stream the content of [entry] to [out] without holding the whole file in memory.
     * The default implementation falls back to [readFile].
     */
    fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)? = null): Boolean {
        val data = readFile(entry) ?: return false
        out.write(data)
        onProgress?.invoke(data.size.toLong())
        return true
    }

    /**
     * Recursively search for files whose name contains [query] (case-insensitive).
     * Default implementation does a depth-limited scan from root.
     */
    fun searchFiles(query: String, volumeIndex: Int = 0): List<FileSystemEntry> {
        val results = mutableListOf<FileSystemEntry>()
        searchRecursive(volumeIndex, "/", query.lowercase(), results, 0, 10)
        return results
    }

    /**
     * Random access: copy up to [len] bytes of the file starting at [offset] into [buf]. Returns the number of
     * bytes read (0 at/after EOF, -1 on error). The default streams from the start; readers override it.
     */
    fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        if (offset >= entry.size) return 0
        var skipped = 0L; var filled = 0
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, o: Int, l: Int) {
                var start = o; var count = l
                if (skipped < offset) { val s = minOf(offset - skipped, count.toLong()).toInt(); skipped += s; start += s; count -= s }
                if (count > 0 && filled < len) { val c = minOf(count, len - filled); System.arraycopy(b, start, buf, bufOff + filled, c); filled += c }
                if (filled >= len) throw java.io.IOException("range complete")
            }
        }
        try { readFileTo(entry, sink) } catch (e: java.io.IOException) { if (filled < len) return -1 }
        return filled
    }

    /** The root directory as an entry usable as the parent for write operations. */
    fun rootEntry(volumeIndex: Int = 0): FileSystemEntry =
        FileSystemEntry(name = "/", path = "/", isDirectory = true, size = 0, createdAt = 0, modifiedAt = 0)

    /** Release resources. */
    fun unmount()
}

private fun FileSystemReader.searchRecursive(
    volumeIndex: Int,
    path: String,
    query: String,
    results: MutableList<FileSystemEntry>,
    depth: Int,
    maxDepth: Int
) {
    if (depth > maxDepth) return
    val entries = try { listDirectory(volumeIndex, path) } catch (e: Exception) { return }
    for (entry in entries) {
        if (entry.name.lowercase().contains(query)) results.add(entry)
        if (entry.isDirectory) searchRecursive(volumeIndex, entry.path, query, results, depth + 1, maxDepth)
    }
}
