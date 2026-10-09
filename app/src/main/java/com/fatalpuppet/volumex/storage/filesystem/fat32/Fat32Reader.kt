package com.fatalpuppet.volumex.storage.filesystem.fat32

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Fat32Reader(
    private val blockDevice: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {

    companion object {
        private const val TAG = "VolumeX"
    }

    private var header: Fat32VolumeHeader? = null

    fun getVolumeHeader(): Fat32VolumeHeader? = header

    override fun mount(): Boolean {
        val sector = blockDevice.readSector(partitionStartLba) ?: run {
            Log.e(TAG, "FAT32: failed to read boot sector at LBA $partitionStartLba")
            return false
        }
        header = Fat32VolumeHeaderParser.parse(sector, partitionStartLba) ?: run {
            Log.e(TAG, "FAT32: not a valid FAT32 boot sector")
            return false
        }
        Log.i(TAG, "FAT32: mounted ${header!!.volumeLabel}, sectorsPerCluster=${header!!.sectorsPerCluster}")
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val h = header ?: return emptyList()
        return listOf(
            VolumeInfo(
                name = h.volumeLabel.ifEmpty { "FAT32 Volume" },
                type = "FAT32",
                uuid = "",
                totalBlocks = h.totalSectors32,
                blockSize = h.bytesPerSector.toLong()
            )
        )
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val h = header ?: return emptyList()
        val startCluster = if (path == "/" || path.isEmpty()) {
            h.rootCluster
        } else {
            resolvePathCluster(path) ?: return emptyList()
        }
        return listClusterDirectory(startCluster, path)
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val h = header ?: return null
        val cluster = entry.inodeOid // we store first cluster in inodeOid
        if (cluster < 2) return ByteArray(0)
        val raw = readClusterChain(cluster)
        return if (entry.size > 0) raw.copyOf(entry.size.coerceAtMost(raw.size.toLong()).toInt()) else raw
    }

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val h = header ?: return false
        if (entry.size <= 0) return true
        val bpc = h.clusterSize
        val maxRun = ((1 shl 20) / bpc).coerceAtLeast(1)
        var remaining = entry.size
        var cluster = entry.inodeOid
        var total = 0L
        while (remaining > 0 && cluster >= 2 && cluster < Fat32Constants.FAT32_EOC_MIN) {
            val runStart = cluster
            var runLen = 1
            var nxt = nextCluster(cluster)
            while (runLen < maxRun && nxt == runStart + runLen && runLen.toLong() * bpc < remaining) {
                runLen++; nxt = nextCluster(runStart + runLen - 1)
            }
            val data = blockDevice.readSectors(clusterToLba(runStart), runLen * h.sectorsPerCluster) ?: return false
            val n = minOf(remaining, data.size.toLong()).toInt()
            out.write(data, 0, n)
            remaining -= n; total += n
            onProgress?.invoke(total)
            cluster = nxt
        }
        return remaining <= 0
    }

    override fun searchFiles(query: String, volumeIndex: Int): List<FileSystemEntry> {
        val results = mutableListOf<FileSystemEntry>()
        searchRecursive("/", query.lowercase(), results, depth = 0, maxDepth = 12)
        return results
    }

    override fun rootEntry(volumeIndex: Int): FileSystemEntry = FileSystemEntry(
        name = "/", path = "/", isDirectory = true, size = 0, createdAt = 0, modifiedAt = 0,
        inodeOid = header?.rootCluster ?: 2L
    )

    override fun unmount() {
        header = null
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun clusterToLba(cluster: Long): Long {
        val h = header!!
        return h.dataAreaLba + (cluster - 2) * h.sectorsPerCluster
    }

    private fun nextCluster(cluster: Long): Long {
        val h = header!!
        val fatOffset = cluster * 4
        val fatSector = h.fatStartLba + fatOffset / h.bytesPerSector
        val sectorOffset = (fatOffset % h.bytesPerSector).toInt()
        val sector = blockDevice.readSector(fatSector) ?: return Fat32Constants.FAT32_EOC_MIN
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(sectorOffset)
        return buf.getInt().toLong() and 0x0FFFFFFFL
    }

    private fun readClusterChain(startCluster: Long): ByteArray {
        val h = header!!
        val clusterSize = h.clusterSize
        val chunks = mutableListOf<ByteArray>()
        var cluster = startCluster
        var safety = 0
        while (cluster >= 2 && cluster < Fat32Constants.FAT32_EOC_MIN && safety++ < 0x0FFFFFF0) {
            val clusterData = blockDevice.readSectors(clusterToLba(cluster), h.sectorsPerCluster)
            chunks.add(clusterData ?: ByteArray(0))
            cluster = nextCluster(cluster)
        }
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(result, pos)
            pos += chunk.size
        }
        return result
    }

    private fun listClusterDirectory(startCluster: Long, path: String): List<FileSystemEntry> {
        val data = readClusterChain(startCluster)
        return parseDirEntries(data, path)
    }

    private fun parseDirEntries(data: ByteArray, parentPath: String): List<FileSystemEntry> {
        val entries = mutableListOf<FileSystemEntry>()
        val dirSize = Fat32Constants.DIR_ENTRY_SIZE
        var i = 0
        var lfnBuffer = mutableListOf<String>()

        while (i + dirSize <= data.size) {
            val first = data[i].toInt() and 0xFF
            if (first == 0x00) break // end of directory
            if (first == 0xE5) { i += dirSize; lfnBuffer.clear(); continue } // deleted

            val attr = data[i + 11].toInt() and 0xFF
            if (attr == Fat32Constants.ATTR_LONG_NAME) {
                // LFN entry
                val seq = first
                val name = extractLfnChars(data, i)
                lfnBuffer.add(0, name)
                i += dirSize
                continue
            }

            if (attr and Fat32Constants.ATTR_VOLUME_ID != 0 && attr and Fat32Constants.ATTR_DIRECTORY == 0) {
                i += dirSize; lfnBuffer.clear(); continue
            }

            val shortName = buildShortName(data, i)
            if (shortName == "." || shortName == "..") {
                i += dirSize; lfnBuffer.clear(); continue
            }

            val longName = if (lfnBuffer.isNotEmpty()) lfnBuffer.joinToString("") else shortName
            lfnBuffer.clear()

            val isDir = attr and Fat32Constants.ATTR_DIRECTORY != 0
            val firstClusterHigh = ((data[i + 20].toInt() and 0xFF) or ((data[i + 21].toInt() and 0xFF) shl 8)).toLong()
            val firstClusterLow = ((data[i + 26].toInt() and 0xFF) or ((data[i + 27].toInt() and 0xFF) shl 8)).toLong()
            val firstCluster = (firstClusterHigh shl 16) or firstClusterLow

            val fileSize = ByteBuffer.wrap(data, i + 28, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL

            val writeDateRaw = ByteBuffer.wrap(data, i + 24, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
            val writeTimeRaw = ByteBuffer.wrap(data, i + 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
            val modTimeMs = fatDateTimeToMs(writeDateRaw, writeTimeRaw)

            val entryPath = if (parentPath == "/") "/$longName" else "$parentPath/$longName"

            entries.add(
                FileSystemEntry(
                    name = longName,
                    path = entryPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else fileSize,
                    createdAt = modTimeMs,
                    modifiedAt = modTimeMs,
                    inodeOid = firstCluster
                )
            )
            i += dirSize
        }
        return entries
    }

    private fun buildShortName(data: ByteArray, offset: Int): String {
        var namePart = String(data, offset, 8, Charsets.ISO_8859_1).trimEnd()
        var extPart = String(data, offset + 8, 3, Charsets.ISO_8859_1).trimEnd()
        // Byte 12 (NT reserved): bit 3 = base name is lower case, bit 4 = extension is lower case.
        val ntRes = data[offset + 12].toInt()
        if (ntRes and 0x08 != 0) namePart = namePart.lowercase()
        if (ntRes and 0x10 != 0) extPart = extPart.lowercase()
        return if (extPart.isEmpty()) namePart else "$namePart.$extPart"
    }

    private fun extractLfnChars(data: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        // LFN chars: bytes 1-10 (5 UCS-2), 14-25 (6 UCS-2), 28-31 (2 UCS-2)
        val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        for (pos in positions) {
            if (offset + pos + 1 >= data.size) break
            val lo = data[offset + pos].toInt() and 0xFF
            val hi = data[offset + pos + 1].toInt() and 0xFF
            val ch = (hi shl 8) or lo
            if (ch == 0x0000 || ch == 0xFFFF) break
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun resolvePathCluster(path: String): Long? {
        val h = header ?: return null
        val parts = path.trim('/').split("/")
        var cluster = h.rootCluster
        for (part in parts) {
            val dirData = readClusterChain(cluster)
            val dirSize = Fat32Constants.DIR_ENTRY_SIZE
            var found = false
            var i = 0
            var lfnBuffer = mutableListOf<String>()
            while (i + dirSize <= dirData.size) {
                val first = dirData[i].toInt() and 0xFF
                if (first == 0x00) break
                if (first == 0xE5) { i += dirSize; lfnBuffer.clear(); continue }
                val attr = dirData[i + 11].toInt() and 0xFF
                if (attr == Fat32Constants.ATTR_LONG_NAME) {
                    lfnBuffer.add(0, extractLfnChars(dirData, i))
                    i += dirSize; continue
                }
                val shortName = buildShortName(dirData, i)
                val longName = if (lfnBuffer.isNotEmpty()) lfnBuffer.joinToString("") else shortName
                lfnBuffer.clear()
                if (longName == part) {
                    val fcHigh = ((dirData[i + 20].toInt() and 0xFF) or ((dirData[i + 21].toInt() and 0xFF) shl 8)).toLong()
                    val fcLow = ((dirData[i + 26].toInt() and 0xFF) or ((dirData[i + 27].toInt() and 0xFF) shl 8)).toLong()
                    cluster = (fcHigh shl 16) or fcLow
                    found = true
                    break
                }
                i += dirSize
            }
            if (!found) return null
        }
        return cluster
    }

    private fun fatDateTimeToMs(date: Int, time: Int): Long {
        if (date == 0) return 0L
        val year = 1980 + (date shr 9)
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        val hour = time shr 11
        val min = (time shr 5) and 0x3F
        val sec = (time and 0x1F) * 2
        return try {
            java.util.Calendar.getInstance().apply {
                set(year, month - 1, day, hour, min, sec)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (e: Exception) { 0L }
    }

    private fun searchRecursive(
        path: String,
        query: String,
        results: MutableList<FileSystemEntry>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val entries = try { listDirectory(0, path) } catch (e: Exception) { return }
        for (entry in entries) {
            if (entry.name.lowercase().contains(query)) results.add(entry)
            if (entry.isDirectory) searchRecursive(entry.path, query, results, depth + 1, maxDepth)
        }
    }
}
