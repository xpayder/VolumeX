package com.fatalpuppet.volumex.storage.filesystem.exfat

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class ExFatWriter(
    private val blockDevice: BlockDeviceReader,
    private val reader: ExFatReader
) : FileSystemWriter {

    companion object {
        private const val TAG = "VolumeX"
        private const val FAT_FREE = 0x00000000L
        private const val FAT_EOC = 0xFFFFFFF8L
    }

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean {
        return try {
            val boot = reader.getBootSector() ?: return false
            val clusterSize = boot.bytesPerCluster
            val neededClusters = (data.size + clusterSize - 1) / clusterSize
            val firstCluster = allocateClusters(neededClusters.coerceAtLeast(1))
                ?: run { Log.e(TAG, "No free clusters"); return false }

            writeClusterChainData(firstCluster, data)

            val dirEntry = buildFileEntry(name, false, firstCluster, data.size.toLong())
            appendToDirectory(parentEntry.inodeOid, dirEntry)
            blockDevice.flushCache()
            Log.i(TAG, "exFAT writeFile: $name OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exFAT writeFile failed", e)
            false
        }
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean {
        return try {
            val firstCluster = allocateClusters(1)
                ?: run { Log.e(TAG, "No free cluster for dir"); return false }
            writeClusterChainData(firstCluster, ByteArray(reader.getBootSector()!!.bytesPerCluster))

            val dirEntry = buildFileEntry(name, true, firstCluster, 0)
            appendToDirectory(parentEntry.inodeOid, dirEntry)
            blockDevice.flushCache()
            Log.i(TAG, "exFAT createDirectory: $name OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exFAT createDirectory failed", e)
            false
        }
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean {
        return try {
            val parentPath = entry.path.substringBeforeLast('/')
            val parentCluster = if (parentPath.isEmpty() || parentPath == "") {
                reader.getBootSector()!!.rootDirectoryCluster.toLong()
            } else {
                resolveCluster(parentPath)
            }
            markEntryDeleted(parentCluster, entry.name)
            freeClusters(entry.inodeOid)
            blockDevice.flushCache()
            Log.i(TAG, "exFAT deleteEntry: ${entry.name} OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exFAT deleteEntry failed", e)
            false
        }
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean {
        return try {
            val parentPath = entry.path.substringBeforeLast('/')
            val parentCluster = if (parentPath.isEmpty()) {
                reader.getBootSector()!!.rootDirectoryCluster.toLong()
            } else {
                resolveCluster(parentPath)
            }
            markEntryDeleted(parentCluster, entry.name)
            val dirEntry = buildFileEntry(newName, entry.isDirectory, entry.inodeOid, entry.size)
            appendToDirectory(parentCluster, dirEntry)
            blockDevice.flushCache()
            Log.i(TAG, "exFAT rename: ${entry.name} → $newName OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exFAT renameEntry failed", e)
            false
        }
    }

    // ── FAT management ──────────────────────────────────────────────────────────

    private fun readFatEntry(cluster: Long): Long = reader.readFatEntry(cluster)

    private fun writeFatEntry(cluster: Long, value: Long) {
        val boot = reader.getBootSector()!!
        val fatOffset = cluster.toInt() * 4
        val fatLba = boot.partitionStartLba + boot.fatOffset + fatOffset / boot.bytesPerSector
        val sectorOff = fatOffset % boot.bytesPerSector
        val sector = blockDevice.readSector(fatLba)?.clone() ?: ByteArray(boot.bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(sectorOff, value.toInt())
        blockDevice.writeSector(fatLba, sector)
        // Second FAT
        if (boot.numberOfFats >= 2) {
            val fat2Lba = fatLba + boot.fatLength
            val sector2 = blockDevice.readSector(fat2Lba)?.clone() ?: ByteArray(boot.bytesPerSector)
            buf.position(0)
            sector.copyInto(sector2)
            blockDevice.writeSector(fat2Lba, sector2)
        }
    }

    private fun findFreeCluster(startFrom: Long = 2L): Long? {
        val boot = reader.getBootSector()!!
        var cluster = startFrom
        val max = boot.clusterCount.toLong() + 2
        while (cluster < max) {
            if (readFatEntry(cluster) == FAT_FREE) return cluster
            cluster++
        }
        return null
    }

    private fun allocateClusters(count: Int): Long? {
        val clusters = mutableListOf<Long>()
        var searchFrom = 2L
        repeat(count) {
            val c = findFreeCluster(searchFrom) ?: return null
            clusters.add(c)
            writeFatEntry(c, FAT_EOC)
            searchFrom = c + 1
        }
        for (i in 0 until clusters.size - 1) writeFatEntry(clusters[i], clusters[i + 1])
        return clusters.first()
    }

    private fun freeClusters(startCluster: Long) {
        var cluster = startCluster
        while (cluster >= 2 && cluster < FAT_EOC) {
            val next = readFatEntry(cluster)
            writeFatEntry(cluster, FAT_FREE)
            cluster = next
        }
    }

    // ── Cluster I/O ─────────────────────────────────────────────────────────────

    private fun clusterToLba(cluster: Long): Long {
        val b = reader.getBootSector()!!
        return b.partitionStartLba + b.clusterHeapOffset + (cluster - 2) * b.sectorsPerCluster
    }

    private fun writeCluster(cluster: Long, data: ByteArray) {
        val b = reader.getBootSector()!!
        val lba = clusterToLba(cluster)
        val padded = if (data.size < b.bytesPerCluster) {
            ByteArray(b.bytesPerCluster).also { data.copyInto(it) }
        } else data
        for (s in 0 until b.sectorsPerCluster) {
            val off = s * b.bytesPerSector
            blockDevice.writeSector(lba + s, padded.copyOfRange(off, off + b.bytesPerSector))
        }
    }

    private fun writeClusterChainData(startCluster: Long, data: ByteArray) {
        val clusterSize = reader.getBootSector()!!.bytesPerCluster
        var cluster = startCluster
        var offset = 0
        while (cluster >= 2 && cluster < FAT_EOC && offset < data.size) {
            val chunk = data.copyOfRange(offset, (offset + clusterSize).coerceAtMost(data.size))
            writeCluster(cluster, chunk)
            offset += clusterSize
            cluster = readFatEntry(cluster)
        }
    }

    // ── Directory entry building ─────────────────────────────────────────────────

    private fun buildFileEntry(name: String, isDir: Boolean, cluster: Long, size: Long): ByteArray {
        val nameChars = name.toCharArray()
        val nameExtCount = (nameChars.size + 14) / 15
        val secondaryCount = 1 + nameExtCount // stream + name extensions
        val totalBytes = 32 * (1 + secondaryCount)
        val buf = ByteArray(totalBytes)
        val cal = Calendar.getInstance()

        // ── File entry (0x85) ──
        buf[0] = 0x85.toByte()
        buf[1] = secondaryCount.toByte()
        // attributes: 0x10 = dir, 0x20 = archive
        val attr = if (isDir) 0x10 else 0x20
        ByteBuffer.wrap(buf, 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(attr.toShort())
        // Timestamps (simplified)
        val fatDate = ((cal.get(Calendar.YEAR) - 1980) shl 9) or
                ((cal.get(Calendar.MONTH) + 1) shl 5) or
                cal.get(Calendar.DAY_OF_MONTH)
        val fatTime = (cal.get(Calendar.HOUR_OF_DAY) shl 11) or
                (cal.get(Calendar.MINUTE) shl 5) or
                (cal.get(Calendar.SECOND) / 2)
        ByteBuffer.wrap(buf, 8, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatTime.toShort())
        ByteBuffer.wrap(buf, 10, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatDate.toShort())
        ByteBuffer.wrap(buf, 12, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatTime.toShort())
        ByteBuffer.wrap(buf, 14, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatDate.toShort())
        ByteBuffer.wrap(buf, 16, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatTime.toShort())
        ByteBuffer.wrap(buf, 18, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(fatDate.toShort())

        // ── Stream Extension (0xC0) ──
        val streamOff = 32
        buf[streamOff] = 0xC0.toByte()
        buf[streamOff + 1] = 0x01 // general secondary flags: AllocationPossible
        buf[streamOff + 3] = nameChars.size.toByte()
        // valid data length + data length
        ByteBuffer.wrap(buf, streamOff + 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(size)
        ByteBuffer.wrap(buf, streamOff + 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(cluster.toInt())
        ByteBuffer.wrap(buf, streamOff + 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(size)

        // ── File Name Extensions (0xC1) ──
        var charIdx = 0
        for (ext in 0 until nameExtCount) {
            val nameOff = 64 + ext * 32
            buf[nameOff] = 0xC1.toByte()
            buf[nameOff + 1] = 0x01 // general secondary flags
            for (k in 0 until 15) {
                val ch = if (charIdx < nameChars.size) nameChars[charIdx++].code else 0x0000
                val off = nameOff + 2 + k * 2
                buf[off] = (ch and 0xFF).toByte()
                buf[off + 1] = ((ch shr 8) and 0xFF).toByte()
            }
        }

        return buf
    }

    private fun appendToDirectory(dirCluster: Long, entryData: ByteArray) {
        val data = reader.readClusterChain(dirCluster)
        val buf = ByteArray(data.size + entryData.size)
        data.copyInto(buf)

        // Find a free slot at the end (first 0x00 entry)
        var insertAt = data.size // default: append
        var i = 0
        while (i + 32 <= data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == 0x00) { insertAt = i; break }
            i += 32
        }

        // Write entry at insertAt, mark terminator after
        entryData.copyInto(buf, insertAt)
        val terminatorAt = insertAt + entryData.size
        if (terminatorAt < buf.size) buf[terminatorAt] = 0x00

        writeDirectoryClusterChain(dirCluster, buf)
    }

    private fun markEntryDeleted(dirCluster: Long, name: String) {
        val data = reader.readClusterChain(dirCluster).clone()
        var i = 0
        while (i + 32 <= data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == 0x00) break
            if (type == 0x85) {
                val secondaryCount = data[i + 1].toInt() and 0xFF
                val entryName = extractEntryName(data, i, secondaryCount)
                if (entryName.equals(name, ignoreCase = true)) {
                    for (j in 0..secondaryCount) {
                        data[i + j * 32] = (data[i + j * 32].toInt() and 0x7F).toByte() // clear InUse bit
                    }
                    writeDirectoryClusterChain(dirCluster, data)
                    return
                }
                i += 32 * (secondaryCount + 1)
            } else {
                i += 32
            }
        }
    }

    private fun extractEntryName(data: ByteArray, fileOffset: Int, secondaryCount: Int): String {
        val sb = StringBuilder()
        for (s in 1..secondaryCount) {
            val off = fileOffset + s * 32
            if (off >= data.size) break
            val type = data[off].toInt() and 0xFF
            if (type and 0x7F == 0x41) { // FileName extension
                for (k in 0 until 15) {
                    val cOff = off + 2 + k * 2
                    if (cOff + 1 >= data.size) break
                    val ch = ((data[cOff + 1].toInt() and 0xFF) shl 8) or (data[cOff].toInt() and 0xFF)
                    if (ch == 0) break
                    sb.append(ch.toChar())
                }
            }
        }
        return sb.toString()
    }

    private fun writeDirectoryClusterChain(startCluster: Long, data: ByteArray) {
        val b = reader.getBootSector()!!
        val clusterSize = b.bytesPerCluster
        var cluster = startCluster
        var offset = 0
        while (cluster >= 2 && cluster < FAT_EOC) {
            val chunk = data.copyOfRange(offset, (offset + clusterSize).coerceAtMost(data.size))
            writeCluster(cluster, chunk)
            offset += clusterSize
            if (offset >= data.size) break
            cluster = readFatEntry(cluster)
        }
    }

    private fun resolveCluster(path: String): Long {
        val b = reader.getBootSector()!!
        val parts = path.trim('/').split("/")
        var cluster = b.rootDirectoryCluster.toLong()
        for (part in parts) {
            val entries = reader.listDirectory(0, if (cluster == b.rootDirectoryCluster.toLong()) "/" else path)
            cluster = entries.firstOrNull { it.name == part }?.inodeOid ?: cluster
        }
        return cluster
    }
}
