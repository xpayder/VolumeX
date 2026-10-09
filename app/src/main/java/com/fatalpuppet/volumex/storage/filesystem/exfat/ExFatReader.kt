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
        return listOf(
            VolumeInfo(
                name = volumeLabel,
                type = "exFAT",
                totalBlocks = b.clusterCount.toLong(),
                blockSize = b.bytesPerCluster.toLong()
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
        return listExFatDirectory(startCluster, path)
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val b = boot ?: return null
        val startCluster = entry.inodeOid
        if (startCluster < 2) return ByteArray(0)
        val raw = readClusterChain(startCluster)
        return if (entry.size > 0) raw.copyOf(entry.size.coerceAtMost(raw.size.toLong()).toInt()) else raw
    }

    override fun unmount() { boot = null }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    fun getBootSector(): ExFatBootSector? = boot

    private fun clusterToLba(cluster: Long): Long {
        val b = boot!!
        return partitionStartLba + b.clusterHeapOffset + (cluster - 2) * b.sectorsPerCluster
    }

    fun readClusterChain(startCluster: Long): ByteArray {
        val b = boot!!
        val chunks = mutableListOf<ByteArray>()
        var cluster = startCluster
        var safety = 0
        while (cluster >= 2 && cluster < FAT_EOC && safety++ < 1_000_000) {
            val lba = clusterToLba(cluster)
            val clusterData = ByteArray(b.bytesPerCluster)
            for (s in 0 until b.sectorsPerCluster) {
                val sd = blockDevice.readSector(lba + s) ?: break
                sd.copyInto(clusterData, s * b.bytesPerSector)
            }
            chunks.add(clusterData)
            cluster = readFatEntry(cluster)
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

    private fun listExFatDirectory(startCluster: Long, parentPath: String): List<FileSystemEntry> {
        val data = readClusterChain(startCluster)
        return parseExFatDirEntries(data, parentPath)
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

                val modTime = 0L // TODO: parse timestamps from dir entry if needed
                val entryPath = if (parentPath == "/") "/$name" else "$parentPath/$name"

                entries.add(FileSystemEntry(
                    name = name,
                    path = entryPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else dataLength,
                    createdAt = modTime,
                    modifiedAt = modTime,
                    inodeOid = firstCluster
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
        for (part in parts) {
            val entries = listExFatDirectory(cluster, "")
            cluster = entries.firstOrNull { it.name.equals(part, ignoreCase = true) }?.inodeOid ?: return null
        }
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
