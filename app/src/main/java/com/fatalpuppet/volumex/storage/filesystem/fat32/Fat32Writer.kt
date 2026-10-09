package com.fatalpuppet.volumex.storage.filesystem.fat32

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class Fat32Writer(
    private val blockDevice: BlockDeviceReader,
    private val header: Fat32VolumeHeader
) : FileSystemWriter {

    companion object {
        private const val TAG = "VolumeX"
        private const val FAT32_FREE = 0x00000000L
        private const val FAT32_EOC = 0x0FFFFFF8L
        private const val DELETED_MARKER = 0xE5.toByte()
        private const val DIR_ENTRY_SIZE = 32
    }

    // ── Public interface ────────────────────────────────────────────────────────

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean {
        return try {
            val parentCluster = parentEntry.inodeOid
            val neededClusters = (data.size + header.clusterSize - 1) / header.clusterSize
            val firstCluster = allocateClusters(neededClusters.coerceAtLeast(1))
                ?: run { Log.e(TAG, "writeFile: no free clusters"); return false }

            writeClusterChain(firstCluster, data)

            val (date, time) = currentFatDateTime()
            val shortName = generateShortName(name, parentCluster)
            val dirEntry = buildDirEntry(shortName, false, firstCluster, data.size.toLong(), date, time)
            val lfnEntries = buildLfnEntries(name, shortName, checksum83(shortName))

            appendDirEntries(parentCluster, lfnEntries + listOf(dirEntry))
            blockDevice.flushCache()
            Log.i(TAG, "writeFile: $name (${data.size} bytes) OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeFile failed", e)
            false
        }
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean {
        return try {
            val parentCluster = parentEntry.inodeOid
            val cluster = allocateClusters(1)
                ?: run { Log.e(TAG, "createDirectory: no free cluster"); return false }

            // Zero out the new cluster
            val zeroCluster = ByteArray(header.clusterSize)
            writeClusterData(cluster, zeroCluster)

            // Write . and .. entries
            val (date, time) = currentFatDateTime()
            val dotEntry = buildDirEntry(".       ", true, cluster, 0, date, time, attr = 0x10)
            val dotdotCluster = if (parentCluster == header.rootCluster) 0L else parentCluster
            val dotdotEntry = buildDirEntry("..      ", true, dotdotCluster, 0, date, time, attr = 0x10)

            val dotData = ByteArray(DIR_ENTRY_SIZE * 2)
            dotEntry.copyInto(dotData, 0)
            dotdotEntry.copyInto(dotData, DIR_ENTRY_SIZE)
            writeClusterData(cluster, dotData)

            val shortName = generateShortName(name, parentCluster)
            val dirEntry = buildDirEntry(shortName, true, cluster, 0, date, time, attr = 0x10)
            val lfnEntries = buildLfnEntries(name, shortName, checksum83(shortName))

            appendDirEntries(parentCluster, lfnEntries + listOf(dirEntry))
            blockDevice.flushCache()
            Log.i(TAG, "createDirectory: $name OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed", e)
            false
        }
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean {
        return try {
            val parentCluster = entry.parentOid
            if (deleteDirEntry(parentCluster, entry.name)) {
                freeClusters(entry.inodeOid)
                blockDevice.flushCache()
                Log.i(TAG, "deleteEntry: ${entry.name} OK")
                true
            } else {
                Log.e(TAG, "deleteEntry: could not find dir entry for ${entry.name}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteEntry failed", e)
            false
        }
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean {
        return try {
            val parentCluster = entry.parentOid
            // Delete old entry (keep clusters)
            deleteDirEntry(parentCluster, entry.name)
            // Create new entry pointing to same cluster
            val (date, time) = currentFatDateTime()
            val shortName = generateShortName(newName, parentCluster)
            val dirEntry = buildDirEntry(shortName, entry.isDirectory, entry.inodeOid,
                entry.size, date, time, attr = if (entry.isDirectory) 0x10 else 0x20)
            val lfnEntries = buildLfnEntries(newName, shortName, checksum83(shortName))
            appendDirEntries(parentCluster, lfnEntries + listOf(dirEntry))
            blockDevice.flushCache()
            Log.i(TAG, "renameEntry: ${entry.name} → $newName OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "renameEntry failed", e)
            false
        }
    }

    // ── FAT cluster management ──────────────────────────────────────────────────

    private fun readFatEntry(cluster: Long): Long {
        val fatOffset = cluster * 4
        val fatSector = header.fatStartLba + fatOffset / header.bytesPerSector
        val sectorOffset = (fatOffset % header.bytesPerSector).toInt()
        val sector = blockDevice.readSector(fatSector) ?: return FAT32_EOC
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(sectorOffset)
        return buf.getInt().toLong() and 0x0FFFFFFFL
    }

    private fun writeFatEntry(cluster: Long, value: Long) {
        val fatOffset = cluster * 4
        val fatSector = header.fatStartLba + fatOffset / header.bytesPerSector
        val sectorOffset = (fatOffset % header.bytesPerSector).toInt()
        val sector = blockDevice.readSector(fatSector)?.clone() ?: ByteArray(header.bytesPerSector.toInt())
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(sectorOffset)
        // Preserve upper 4 bits (reserved), write lower 28 bits
        val existing = buf.getInt(sectorOffset).toLong() and 0xFFFFFFFF
        val newVal = (existing and 0xF0000000L) or (value and 0x0FFFFFFFL)
        buf.position(sectorOffset)
        buf.putInt(newVal.toInt())
        blockDevice.writeSector(fatSector, sector)
        // Write to second FAT if present
        if (header.fatCount >= 2) {
            val fat2Sector = fatSector + header.fatSize32
            val sector2 = blockDevice.readSector(fat2Sector)?.clone() ?: ByteArray(header.bytesPerSector.toInt())
            sector.copyInto(sector2)
            blockDevice.writeSector(fat2Sector, sector2)
        }
    }

    private fun findFreeCluster(startFrom: Long = 2L): Long? {
        val maxCluster = header.totalSectors32 / header.sectorsPerCluster + 2
        var cluster = startFrom
        while (cluster < maxCluster) {
            if (readFatEntry(cluster) == FAT32_FREE) return cluster
            cluster++
        }
        return null
    }

    private fun allocateClusters(count: Int): Long? {
        if (count == 0) return 0L
        val clusters = mutableListOf<Long>()
        var searchFrom = 2L
        repeat(count) {
            val c = findFreeCluster(searchFrom) ?: return null
            clusters.add(c)
            writeFatEntry(c, FAT32_EOC) // mark as end of chain temporarily
            searchFrom = c + 1
        }
        // Build chain
        for (i in 0 until clusters.size - 1) {
            writeFatEntry(clusters[i], clusters[i + 1])
        }
        // Last one stays EOC
        return clusters.first()
    }

    private fun freeClusters(startCluster: Long) {
        var cluster = startCluster
        while (cluster >= 2 && cluster < FAT32_EOC) {
            val next = readFatEntry(cluster)
            writeFatEntry(cluster, FAT32_FREE)
            cluster = next
        }
    }

    // ── Cluster I/O ─────────────────────────────────────────────────────────────

    private fun clusterToLba(cluster: Long): Long =
        header.dataAreaLba + (cluster - 2) * header.sectorsPerCluster

    private fun writeClusterData(cluster: Long, data: ByteArray) {
        val lba = clusterToLba(cluster)
        val clusterSize = header.clusterSize
        val padded = if (data.size < clusterSize) {
            ByteArray(clusterSize).also { data.copyInto(it) }
        } else data
        for (s in 0 until header.sectorsPerCluster) {
            val off = s * header.bytesPerSector.toInt()
            val sector = padded.copyOfRange(off, off + header.bytesPerSector.toInt())
            blockDevice.writeSector(lba + s, sector)
        }
    }

    private fun writeClusterChain(startCluster: Long, data: ByteArray) {
        val clusterSize = header.clusterSize
        var cluster = startCluster
        var offset = 0
        while (cluster >= 2 && cluster < FAT32_EOC && offset < data.size) {
            val chunk = data.copyOfRange(offset, (offset + clusterSize).coerceAtMost(data.size))
            writeClusterData(cluster, chunk)
            offset += clusterSize
            cluster = readFatEntry(cluster)
        }
    }

    // ── Directory entry management ───────────────────────────────────────────────

    private fun appendDirEntries(dirCluster: Long, entries: List<ByteArray>) {
        // Read cluster chain and find free slots
        val dirData = readClusterChain(dirCluster).toMutableList()
        val totalEntries = entries.size

        // Find first run of 'totalEntries' free or deleted slots
        var startSlot = -1
        var consecutive = 0
        var i = 0
        while (i * DIR_ENTRY_SIZE < dirData.size) {
            val first = dirData[i * DIR_ENTRY_SIZE].toInt() and 0xFF
            if (first == 0x00 || first == 0xE5) {
                if (startSlot < 0) startSlot = i
                consecutive++
                if (consecutive >= totalEntries) break
            } else {
                startSlot = -1
                consecutive = 0
            }
            i++
        }

        if (startSlot < 0) {
            // Need to expand — allocate one more cluster
            val existingClusters = getClusterChain(dirCluster)
            val newCluster = allocateClusters(1) ?: return
            writeFatEntry(existingClusters.last(), newCluster)
            writeClusterData(newCluster, ByteArray(header.clusterSize))
            // Re-read
            val newDirData = readClusterChain(dirCluster).toMutableList()
            startSlot = newDirData.size / DIR_ENTRY_SIZE
            repeat(totalEntries) { newDirData.addAll(ByteArray(DIR_ENTRY_SIZE).toList()) }
            writeDirectoryClusterChain(dirCluster, newDirData.toByteArray())
            return
        }

        // Write entries into the found slots
        for ((idx, entry) in entries.withIndex()) {
            val slot = startSlot + idx
            val byteOff = slot * DIR_ENTRY_SIZE
            entry.copyInto(dirData.toByteArray(), byteOff)
        }
        // Ensure terminator after
        val terminatorSlot = startSlot + totalEntries
        if (terminatorSlot * DIR_ENTRY_SIZE < dirData.size) {
            dirData[terminatorSlot * DIR_ENTRY_SIZE] = 0x00
        }

        writeDirectoryClusterChain(dirCluster, dirData.toByteArray())
    }

    private fun deleteDirEntry(dirCluster: Long, name: String): Boolean {
        val data = readClusterChain(dirCluster)
        val buf = data.clone()
        var i = 0
        var lfnStart = -1
        while (i * DIR_ENTRY_SIZE + DIR_ENTRY_SIZE <= buf.size) {
            val first = buf[i * DIR_ENTRY_SIZE].toInt() and 0xFF
            if (first == 0x00) break
            val attr = buf[i * DIR_ENTRY_SIZE + 11].toInt() and 0xFF
            if (attr == 0x0F) { // LFN
                if (lfnStart < 0) lfnStart = i
                i++; continue
            }
            val shortName = String(buf, i * DIR_ENTRY_SIZE, 11).trim()
            val lfnName = extractLfnName(buf, lfnStart, i)
            val entryName = lfnName.ifEmpty { shortName }
            if (entryName.equals(name, ignoreCase = true) || shortName.replace(" ", "").equals(
                    name.replace(".", "").uppercase().take(11), ignoreCase = true)) {
                // Mark all as deleted
                val delStart = if (lfnStart >= 0) lfnStart else i
                for (j in delStart..i) {
                    buf[j * DIR_ENTRY_SIZE] = DELETED_MARKER
                }
                writeDirectoryClusterChain(dirCluster, buf)
                return true
            }
            lfnStart = -1
            i++
        }
        return false
    }

    private fun writeDirectoryClusterChain(startCluster: Long, data: ByteArray) {
        val clusterSize = header.clusterSize
        var cluster = startCluster
        var offset = 0
        while (cluster >= 2 && cluster < FAT32_EOC) {
            val chunk = data.copyOfRange(offset, (offset + clusterSize).coerceAtMost(data.size))
            writeClusterData(cluster, chunk)
            offset += clusterSize
            if (offset >= data.size) break
            cluster = readFatEntry(cluster)
        }
    }

    private fun readClusterChain(startCluster: Long): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var cluster = startCluster
        var safety = 0
        while (cluster >= 2 && cluster < FAT32_EOC && safety++ < 100_000) {
            val lba = clusterToLba(cluster)
            val clusterData = ByteArray(header.clusterSize)
            for (s in 0 until header.sectorsPerCluster) {
                val sectorData = blockDevice.readSector(lba + s) ?: break
                sectorData.copyInto(clusterData, s * header.bytesPerSector.toInt())
            }
            chunks.add(clusterData)
            cluster = readFatEntry(cluster)
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(result, pos); pos += c.size }
        return result
    }

    private fun getClusterChain(startCluster: Long): List<Long> {
        val result = mutableListOf<Long>()
        var cluster = startCluster
        var safety = 0
        while (cluster >= 2 && cluster < FAT32_EOC && safety++ < 100_000) {
            result.add(cluster)
            cluster = readFatEntry(cluster)
        }
        return result
    }

    // ── Directory entry building ─────────────────────────────────────────────────

    private fun buildDirEntry(
        shortName: String,
        isDir: Boolean,
        cluster: Long,
        size: Long,
        date: Int,
        time: Int,
        attr: Int = if (isDir) 0x10 else 0x20
    ): ByteArray {
        val entry = ByteArray(DIR_ENTRY_SIZE)
        // Name: 8+3 padded with spaces
        val name8 = shortName.padEnd(8).take(8)
        val ext3 = (if (shortName.contains('.')) shortName.substringAfterLast('.') else "")
            .padEnd(3).take(3)
        for (i in 0..7) entry[i] = name8[i].code.toByte()
        for (i in 0..2) entry[8 + i] = ext3[i].code.toByte()
        entry[11] = attr.toByte()
        // cluster high (bytes 20-21)
        entry[20] = ((cluster shr 16) and 0xFF).toByte()
        entry[21] = ((cluster shr 24) and 0xFF).toByte()
        // time/date
        entry[22] = (time and 0xFF).toByte()
        entry[23] = ((time shr 8) and 0xFF).toByte()
        entry[24] = (date and 0xFF).toByte()
        entry[25] = ((date shr 8) and 0xFF).toByte()
        // cluster low (bytes 26-27)
        entry[26] = (cluster and 0xFF).toByte()
        entry[27] = ((cluster shr 8) and 0xFF).toByte()
        // size (bytes 28-31)
        entry[28] = (size and 0xFF).toByte()
        entry[29] = ((size shr 8) and 0xFF).toByte()
        entry[30] = ((size shr 16) and 0xFF).toByte()
        entry[31] = ((size shr 24) and 0xFF).toByte()
        return entry
    }

    private fun buildLfnEntries(longName: String, shortName: String, checksum: Byte): List<ByteArray> {
        if (longName.length <= 12 && longName == longName.uppercase() && !longName.contains(' ')) {
            return emptyList() // No LFN needed for simple short names
        }
        // Each LFN entry holds 13 UCS-2 characters
        val chars = longName.toCharArray()
        val numEntries = (chars.size + 12) / 13
        val result = mutableListOf<ByteArray>()

        for (seq in numEntries downTo 1) {
            val entry = ByteArray(DIR_ENTRY_SIZE)
            val seqByte = if (seq == numEntries) (seq or 0x40).toByte() else seq.toByte()
            entry[0] = seqByte
            entry[11] = 0x0F // LFN attribute
            entry[13] = checksum

            val startChar = (seq - 1) * 13
            val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for ((idx, pos) in positions.withIndex()) {
                val charIdx = startChar + idx
                val ch: Int = when {
                    charIdx < chars.size -> chars[charIdx].code
                    charIdx == chars.size -> 0x0000
                    else -> 0xFFFF
                }
                entry[pos] = (ch and 0xFF).toByte()
                entry[pos + 1] = ((ch shr 8) and 0xFF).toByte()
            }
            result.add(entry)
        }
        return result
    }

    private fun generateShortName(longName: String, dirCluster: Long): String {
        // Basic 8.3 generation: uppercase, remove spaces, truncate
        val dot = longName.lastIndexOf('.')
        val base = (if (dot > 0) longName.substring(0, dot) else longName)
            .filter { it.isLetterOrDigit() || it in "-_" }
            .uppercase().take(8).padEnd(8)
        val ext = (if (dot >= 0) longName.substring(dot + 1) else "")
            .filter { it.isLetterOrDigit() }.uppercase().take(3)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun checksum83(name: String): Byte {
        val padded = name.filter { it != '.' }.padEnd(11).take(11)
        var sum = 0
        for (c in padded) {
            sum = ((sum and 1 shl 7) or ((sum and 0xFE) ushr 1)) + c.code
        }
        return (sum and 0xFF).toByte()
    }

    private fun extractLfnName(data: ByteArray, lfnStart: Int, sfnIdx: Int): String {
        if (lfnStart < 0) return ""
        val sb = StringBuilder()
        for (i in lfnStart until sfnIdx) {
            val off = i * DIR_ENTRY_SIZE
            val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (pos in positions) {
                if (off + pos + 1 >= data.size) break
                val lo = data[off + pos].toInt() and 0xFF
                val hi = data[off + pos + 1].toInt() and 0xFF
                val ch = (hi shl 8) or lo
                if (ch == 0x0000 || ch == 0xFFFF) return sb.toString()
                sb.append(ch.toChar())
            }
        }
        return sb.toString()
    }

    private fun currentFatDateTime(): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR) - 1980
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND) / 2
        val date = (year shl 9) or (month shl 5) or day
        val time = (hour shl 11) or (min shl 5) or sec
        return Pair(date, time)
    }
}
