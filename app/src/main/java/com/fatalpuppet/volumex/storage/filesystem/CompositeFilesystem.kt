package com.fatalpuppet.volumex.storage.filesystem

/**
 * Presents several mounted partitions as one reader: volume index N is the N-th volume across all
 * partitions, and every entry carries the [FileSystemEntry.partitionId] it came from so that reads
 * and writes are routed to the right filesystem.
 */
class CompositeReader(private val parts: List<FilesystemMounter.MountedPartition>) : FileSystemReader {
    private class Slot(val part: Int, val local: Int)
    private var slots: List<Slot> = emptyList()
    private var infos: List<VolumeInfo> = emptyList()

    override fun mount(): Boolean { refresh(); return parts.isNotEmpty() }

    private fun refresh() {
        val s = ArrayList<Slot>(); val i = ArrayList<VolumeInfo>()
        parts.forEachIndexed { p, m ->
            m.reader.getVolumeInfos().forEachIndexed { l, v ->
                s.add(Slot(p, l))
                i.add(if (parts.size > 1 && v.name.isBlank()) v.copy(name = "Partition ${p + 1}") else v)
            }
        }
        slots = s; infos = i
    }

    override fun getVolumeInfos(): List<VolumeInfo> { if (infos.isEmpty()) refresh(); return infos }

    private fun slot(volumeIndex: Int): Slot? { if (slots.isEmpty()) refresh(); return slots.getOrNull(volumeIndex) }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val s = slot(volumeIndex) ?: return emptyList()
        return parts[s.part].reader.listDirectory(s.local, path).map { it.copy(partitionId = s.part) }
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? = parts.getOrNull(entry.partitionId)?.reader?.readFile(entry)

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean =
        parts.getOrNull(entry.partitionId)?.reader?.readFileTo(entry, out, onProgress) ?: false

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int =
        parts.getOrNull(entry.partitionId)?.reader?.readRange(entry, offset, buf, bufOff, len) ?: -1

    override fun rootEntry(volumeIndex: Int): FileSystemEntry {
        val s = slot(volumeIndex) ?: return super.rootEntry(volumeIndex)
        return parts[s.part].reader.rootEntry(s.local).copy(partitionId = s.part)
    }

    /** The writer for the partition holding [volumeIndex] (null if that filesystem is read-only). */
    fun writerFor(volumeIndex: Int): FileSystemWriter? {
        val s = slot(volumeIndex) ?: return null
        return parts[s.part].writer?.let { PartitionWriter(it, s.part) }
    }

    override fun unmount() { parts.forEach { it.reader.unmount() } }
}

/** Wraps a partition's writer so entries copied across partitions keep their partition id out of the way. */
private class PartitionWriter(private val w: FileSystemWriter, private val part: Int) : FileSystemWriter {
    override fun createDirectory(parentEntry: FileSystemEntry, name: String) = w.createDirectory(parentEntry, name)
    override fun deleteEntry(entry: FileSystemEntry) = w.deleteEntry(entry)
    override fun renameEntry(entry: FileSystemEntry, newName: String) = w.renameEntry(entry, newName)
    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray) = w.writeFile(parentEntry, name, data)
    override fun writeFileStream(parentEntry: FileSystemEntry, name: String, size: Long, input: java.io.InputStream, onProgress: ((Long) -> Unit)?) =
        w.writeFileStream(parentEntry, name, size, input, onProgress)
}
