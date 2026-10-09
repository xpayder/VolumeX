package com.fatalpuppet.volumex.storage.filesystem.exfat

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * exFAT reader. Key structures:
 *  - Boot sector at partitionStartLba
 *  - FAT at partitionStartLba + fatOffset
 *  - Cluster heap at partitionStartLba + clusterHeapOffset
 *  - Root directory at rootDirectoryCluster
 *
 * Directory entries are 32-byte records. Entry types:
 *  0x85 = File entry (primary)
 *  0xC0 = Stream Extension
 *  0xC1 = File Name Extension
 *  0x81 = Allocation Bitmap
 *  0x82 = Up-case Table
 *  0x83 = Volume Label
 */
class ExFatReader(
    private val blockDevice: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {

    companion object {
        private const val TAG = "VolumeX"
        private const val FAT_EOC = 0xFFFFFFF8L
        private const val FAT_FREE = 0x00000000L
    }

    private var boot: ExFatBootSector? = null

    override fun mount(): Boolean {
        val sector = blockDevice.readSector(partitionStartLba) ?: return false
        boot = ExFatBootSectorParser.parse(sector, partitionStartLba) ?: return false
        Log.i(TAG, "exFAT: mounted, bytesPerCluster=${boot!!.bytesPerCluster}")
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val b = boot ?: return emptyList()
        val volumeLabel = readVolumeLabel() ?: "exFAT Volume"
        val free = freeClusterCount()
        return listOf(
            VolumeInfo(
                name = volumeLabel,
                type = "exFAT",
                totalBlocks = b.clusterCount.toLong(),
                blockSize = b.bytesPerCluster.toLong(),
                freeBlocks = free ?: 0L,
                freeKnown = free != null
            )
        )
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val b = boot ?: return emptyList()
        val startCluster = if (path == "/" || path.isEmpty()) {
            b.rootDirectoryCluster.toLong()
        } else {
            resolvePathCluster(path) ?: return emptyList()
        }
        return if (path == "/" || path.isEmpty()) listExFatDirectory(startCluster, path)
        else listExFatDirectory(startCluster, path, resolvedContiguous, resolvedLength)
    }

    private var resolvedContiguous = false
    private var resolvedLength = -1L

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        boot ?: return null
        val startCluster = entry.inodeOid
        if (startCluster < 2 || entry.size <= 0) return ByteArray(0)
        val raw = readClusterChain(startCluster, entry.contiguous, entry.size)
        return raw.copyOf(entry.size.coerceAtMost(raw.size.toLong()).toInt())
    }

    private val chainCache = object : LinkedHashMap<Long, LongArray>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, LongArray>?) = size > 8 }

    private fun chainOf(entry: FileSystemEntry): LongArray {
        val b = boot!!
        synchronized(chainCache) { chainCache[entry.inodeOid]?.let { return it } }
        val count = ((entry.size + b.bytesPerCluster - 1) / b.bytesPerCluster).toInt()
        val arr = LongArray(count)
        var c = entry.inodeOid
        for (i in 0 until count) {
            arr[i] = c
            c = if (entry.contiguous) c + 1 else readFatEntry(c)
        }
        synchronized(chainCache) { chainCache[entry.inodeOid] = arr }
        return arr
    }

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        val b = boot ?: return -1
        if (offset >= entry.size) return 0
        val bpc = b.bytesPerCluster
        val chain = chainOf(entry)
        var pos = offset; var done = 0
        val want = minOf(len.toLong(), entry.size - offset).toInt()
        while (done < want) {
            val ci = (pos / bpc).toInt(); if (ci >= chain.size) break
            val inCluster = (pos % bpc).toInt()
            // extend over physically consecutive clusters
            var run = 1
            while (ci + run < chain.size && chain[ci + run] == chain[ci] + run && (run * bpc - inCluster) < want - done) run++
            val bytes = minOf(want - done, run * bpc - inCluster)
            val firstSector = inCluster / 512
            val sectors = (inCluster % 512 + bytes + 511) / 512
            val data = blockDevice.readSectors(clusterToLba(chain[ci]) + firstSector, sectors) ?: return if (done > 0) done else -1
            System.arraycopy(data, inCluster % 512, buf, bufOff + done, bytes)
            done += bytes; pos += bytes
        }
        return done
    }

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val b = boot ?: return false
        if (entry.size <= 0) return true
        val bpc = b.bytesPerCluster
        val maxRun = ((1 shl 20) / bpc).coerceAtLeast(1)
        var remaining = entry.size
        var cluster = entry.inodeOid
        var total = 0L
        fun nextOf(c: Long) = if (entry.contiguous) c + 1 else readFatEntry(c)
        while (remaining > 0 && cluster >= 2 && cluster < b.clusterCount + 2) {
            val runStart = cluster
            var runLen = 1
            var nxt = nextOf(cluster)
            while (runLen < maxRun && nxt == runStart + runLen && runLen.toLong() * bpc < remaining) {
                runLen++; nxt = nextOf(runStart + runLen - 1)
            }
            val data = blockDevice.readSectors(clusterToLba(runStart), runLen * b.sectorsPerCluster) ?: return false
            val n = minOf(remaining, data.size.toLong()).toInt()
            out.write(data, 0, n)
            remaining -= n; total += n
            onProgress?.invoke(total)
            cluster = nxt
        }
        return remaining <= 0
    }

    override fun rootEntry(volumeIndex: Int): FileSystemEntry = FileSystemEntry(
        name = "/", path = "/", isDirectory = true, size = 0, createdAt = 0, modifiedAt = 0,
        inodeOid = boot?.rootDirectoryCluster ?: 0L
    )

    /** Free clusters, counted from the allocation bitmap (null if it cannot be read). */
    private fun freeClusterCount(): Long? {
        val b = boot ?: return null
        val root = try { readClusterChain(b.rootDirectoryCluster) } catch (_: Exception) { return null }
        var i = 0
        while (i + 32 <= root.size) {
            val t = root[i].toInt() and 0xFF
            if (t == 0) break
            if (t == 0x81) {
                val first = ByteBuffer.wrap(root, i + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
                val len = ByteBuffer.wrap(root, i + 24, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                if (len <= 0 || len > 64L * 1024 * 1024) return null
                val sectors = ((len + b.bytesPerSector - 1) / b.bytesPerSector).toInt()
                val bm = blockDevice.readSectors(clusterToLba(first), sectors) ?: return null
                var used = 0L
                val bits = b.clusterCount
                var n = 0L
                for (byteIdx in 0 until len.toInt()) {
                    val v = bm[byteIdx].toInt() and 0xFF
                    val take = minOf(8L, bits - n).toInt().coerceAtLeast(0)
                    if (take <= 0) break
                    used += Integer.bitCount(v and ((1 shl take) - 1))
                    n += take
                }
                return bits - used
            }
            i += 32
        }
        return null
    }

    override fun unmount() { boot = null }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    fun getBootSector(): ExFatBootSector? = boot

    private fun clusterToLba(cluster: Long): Long {
        val b = boot!!
        return partitionStartLba + b.clusterHeapOffset + (cluster - 2) * b.sectorsPerCluster
    }

    /**
     * Read a cluster chain. When [contiguous] (NoFatChain) the FAT is not used: the stream occupies
     * ceil([length]/clusterSize) consecutive clusters starting at [startCluster].
     */
    fun readClusterChain(startCluster: Long, contiguous: Boolean = false, length: Long = -1L): ByteArray {
        val b = boot!!
        val chunks = mutableListOf<ByteArray>()
        var cluster = startCluster
        var safety = 0
        val contiguousCount = if (contiguous && length > 0) (length + b.bytesPerCluster - 1) / b.bytesPerCluster else 0L
        while (cluster >= 2 && (contiguous || cluster < FAT_EOC) && safety++ < 1_000_000) {
            if (contiguous && safety > contiguousCount) break
            val lba = clusterToLba(cluster)
            val clusterData = blockDevice.readSectors(lba, b.sectorsPerCluster) ?: ByteArray(b.bytesPerCluster)
            chunks.add(clusterData)
            cluster = if (contiguous) cluster + 1 else readFatEntry(cluster)
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(result, pos); pos += c.size }
        return result
    }

    fun readFatEntry(cluster: Long): Long {
        val b = boot!!
        val fatOffset = cluster.toInt() * 4
        val fatLba = partitionStartLba + b.fatOffset + fatOffset / b.bytesPerSector
        val sectorOff = fatOffset % b.bytesPerSector
        val sector = blockDevice.readSector(fatLba) ?: return FAT_EOC
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        return buf.getInt(sectorOff).toLong() and 0xFFFFFFFFL
    }

    private fun listExFatDirectory(startCluster: Long, parentPath: String, contiguous: Boolean = false, length: Long = -1L): List<FileSystemEntry> {
        val data = readClusterChain(startCluster, contiguous, length)
        return parseExFatDirEntries(data, parentPath)
    }

    /** exFAT timestamp: 32-bit DOS date/time (year since 1980, 2-second resolution), treated as UTC. */
    private fun dosToMillis(ts: Int): Long {
        if (ts == 0) return 0L
        val sec = (ts and 0x1F) * 2
        val min = (ts ushr 5) and 0x3F
        val hour = (ts ushr 11) and 0x1F
        val day = (ts ushr 16) and 0x1F
        val month = (ts ushr 21) and 0x0F
        val year = ((ts ushr 25) and 0x7F) + 1980
        if (day == 0 || month == 0) return 0L
        return java.time.LocalDateTime.of(year, month.coerceIn(1, 12), day, hour.coerceIn(0, 23), min.coerceIn(0, 59), sec.coerceIn(0, 59))
            .toEpochSecond(java.time.ZoneOffset.UTC) * 1000L
    }

    private fun parseExFatDirEntries(data: ByteArray, parentPath: String): List<FileSystemEntry> {
        val entries = mutableListOf<FileSystemEntry>()
        var i = 0
        while (i + 32 <= data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == 0x00) break // end of directory

            if (type == 0x85) { // File entry
                val secondaryCount = data[i + 1].toInt() and 0xFF
                if (i + 32 * (secondaryCount + 1) > data.size) break

                val attr = ByteBuffer.wrap(data, i + 4, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
                val isDir = (attr and 0x10) != 0

                // Next entry must be Stream Extension (0xC0)
                var j = i + 32
                if (j + 32 > data.size || (data[j].toInt() and 0xFF) != 0xC0) { i += 32; continue }

                val generalFlags = data[j + 1].toInt() and 0xFF
                val nameLength = data[j + 3].toInt() and 0xFF
                val firstCluster = ByteBuffer.wrap(data, j + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
                val dataLength = ByteBuffer.wrap(data, j + 24, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()

                // Collect File Name Extensions (0xC1)
                val nameSb = StringBuilder()
                j += 32
                while (j + 32 <= data.size && (data[j].toInt() and 0xFF) == 0xC1) {
                    for (k in 0 until 15) {
                        val off = j + 2 + k * 2
                        if (off + 1 >= data.size) break
                        val lo = data[off].toInt() and 0xFF
                        val hi = data[off + 1].toInt() and 0xFF
                        val ch = (hi shl 8) or lo
                        if (ch == 0x0000) break
                        nameSb.append(ch.toChar())
                        if (nameSb.length >= nameLength) break
                    }
                    j += 32
                }

                val name = nameSb.toString().ifEmpty { "<unknown>" }
                if (name == "." || name == "..") { i += 32 * (secondaryCount + 1); continue }

                val createTime = dosToMillis(ByteBuffer.wrap(data, i + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                val modTime = dosToMillis(ByteBuffer.wrap(data, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                val entryPath = if (parentPath == "/") "/$name" else "$parentPath/$name"

                entries.add(FileSystemEntry(
                    name = name,
                    path = entryPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else dataLength,
                    createdAt = createTime,
                    modifiedAt = modTime,
                    inodeOid = firstCluster,
                    contiguous = (generalFlags and 0x02) != 0,
                    allocLength = dataLength
                ))

                i += 32 * (secondaryCount + 1)
            } else {
                i += 32
            }
        }
        return entries
    }

    private fun resolvePathCluster(path: String): Long? {
        val b = boot ?: return null
        val parts = path.trim('/').split("/")
        var cluster = b.rootDirectoryCluster.toLong()
        var contiguous = false
        var length = -1L
        for (part in parts) {
            val entries = listExFatDirectory(cluster, "", contiguous, length)
            val e = entries.firstOrNull { it.name.equals(part, ignoreCase = true) && it.isDirectory } ?: return null
            cluster = e.inodeOid; contiguous = e.contiguous; length = e.allocLength
        }
        resolvedContiguous = contiguous; resolvedLength = length
        return cluster
    }

    private fun readVolumeLabel(): String? {
        val b = boot ?: return null
        val rootData = readClusterChain(b.rootDirectoryCluster.toLong())
        var i = 0
        while (i + 32 <= rootData.size) {
            val type = rootData[i].toInt() and 0xFF
            if (type == 0x00) break
            if (type == 0x83) { // Volume Label
                val charCount = rootData[i + 1].toInt() and 0xFF
                val sb = StringBuilder()
                for (k in 0 until charCount) {
                    val off = i + 2 + k * 2
                    if (off + 1 >= rootData.size) break
                    val ch = ((rootData[off + 1].toInt() and 0xFF) shl 8) or (rootData[off].toInt() and 0xFF)
                    sb.append(ch.toChar())
                }
                return sb.toString()
            }
            i += 32
        }
        return null
    }
}
