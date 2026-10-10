package app.feldkit.storage.filesystem

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.apfs.ApfsContainerSuperblockParser
import app.feldkit.storage.filesystem.apfs.ApfsBTreeParser
import app.feldkit.storage.filesystem.apfs.ApfsOMapParser
import app.feldkit.storage.filesystem.apfs.ApfsVolumeSuperblockParser
import app.feldkit.storage.filesystem.apfs.ApfsReader
import app.feldkit.storage.filesystem.apfs.ApfsWriter
import app.feldkit.storage.filesystem.exfat.ExFatReader
import app.feldkit.storage.filesystem.exfat.ExFatWriter
import app.feldkit.storage.filesystem.fat32.Fat32Reader
import app.feldkit.storage.filesystem.fat32.Fat32Writer
import app.feldkit.storage.filesystem.ext.ExtReader
import app.feldkit.storage.filesystem.hfsplus.HfsPlusReader
import app.feldkit.storage.filesystem.hfsplus.HfsPlusWriter
import app.feldkit.storage.filesystem.hfsplus.HfsPlusVolumeHeaderParser
import app.feldkit.storage.filesystem.lvm.LvmBlockDevice
import app.feldkit.storage.filesystem.lvm.LvmParser
import app.feldkit.storage.filesystem.partition.GptPartitionTable
import app.feldkit.storage.filesystem.partition.MbrPartitionTable

/** Detects and mounts a filesystem on a raw block device (extracted from MainViewModel so it is testable off-device). */
object FilesystemMounter {
    /** Opt-in switch for the experimental APFS writer (Settings > Experimental). */
    @Volatile var enableApfsWrite: Boolean = false
    /** Opt-in switch for the experimental NTFS writer (Settings > Experimental). */
    @Volatile var enableNtfsWrite: Boolean = false
    private const val TAG = "FeldKit"

    /** A filesystem found on the device (one per partition; APFS may expose several volumes). */
    class MountedPartition(val reader: FileSystemReader, val writer: FileSystemWriter?, val startLba: Long)

    /** First filesystem found (kept for callers/tests that handle a single volume). */
    fun mount(device: BlockDeviceReader): Pair<FileSystemReader, FileSystemWriter?>? =
        mountAll(device).firstOrNull()?.let { it.reader to it.writer }

    /**
     * Find every mountable filesystem, driven by the partition table (GPT, then MBR), falling back to
     * probing well-known offsets for unpartitioned media. The EFI system partition is skipped.
     */
    fun mountAll(device: BlockDeviceReader): List<MountedPartition> {
        // LVM logical volume
        if (LvmParser.detect(device)) {
            val pv = LvmParser.parsePV(device)
            val meta = pv?.let { LvmParser.readVgMetadata(device, it) }
            val lvs = meta?.let { LvmParser.parseLogicalVolumes(it) }.orEmpty()
            if (pv != null && meta != null && lvs.isNotEmpty()) {
                Log.i(TAG, "LVM: found ${lvs.size} logical volume(s), using first: ${lvs[0].name}")
                val lv = LvmBlockDevice(device, lvs[0], LvmParser.parseExtentSize(meta))
                return probeStarts(lv, listOf(0L, 40L, 56L, 64L, 128L, 2048L))
            }
        }

        val starts = partitionStarts(device)
        if (starts.isNotEmpty()) {
            val found = probeStarts(device, starts)
            if (found.isNotEmpty()) return found
        }
        return probeStarts(device, listOf(0L, 40L, 56L, 64L, 128L, 2048L))
    }

    private fun probeStarts(device: BlockDeviceReader, starts: List<Long>): List<MountedPartition> {
        val out = ArrayList<MountedPartition>()
        for (lba in starts.distinct()) {
            val m = tryBitLocker(device, lba) ?: tryApfs(device, lba) ?: tryHfsPlus(device, lba) ?: tryNtfs(device, lba) ?: tryFat32(device, lba)
                ?: tryExFat(device, lba) ?: tryExt(device, lba) ?: tryUdf(device, lba) ?: tryIso(device, lba)
            if (m != null) out.add(MountedPartition(m.first, m.second, lba))
        }
        return out
    }

    /** Apple Partition Map (classic Mac disks and many .dmg files): 'ER' driver record at sector 0, 'PM' entries from sector 1. */
    private fun apmStarts(device: BlockDeviceReader): List<Long>? {
        val s0 = device.readSector(0) ?: return null
        if (s0[0] != 'E'.code.toByte() || s0[1] != 'R'.code.toByte()) return null
        val first = device.readSector(1) ?: return null
        if (first[0] != 'P'.code.toByte() || first[1] != 'M'.code.toByte()) return null
        fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        val count = be32(first, 4).toInt().coerceIn(1, 64)
        val out = ArrayList<Long>()
        for (i in 1..count) {
            val e = device.readSector(i.toLong()) ?: break
            if (e[0] != 'P'.code.toByte() || e[1] != 'M'.code.toByte()) break
            val type = String(e, 48, 32, Charsets.US_ASCII).trimEnd('\u0000')
            if (type.startsWith("Apple_HFS") || type.startsWith("Apple_APFS") || type == "Windows_FAT_32" || type == "Apple_UNIX_SVR2" || type == "Linux") out.add(be32(e, 8))
        }
        return out
    }

    private const val EFI_SYSTEM_GUID = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b"

    /** Partition start LBAs from the GPT (preferred) or the MBR; empty if the media is unpartitioned. */
    private fun partitionStarts(device: BlockDeviceReader): List<Long> {
        val mbr = device.readSector(0) ?: return emptyList()
        apmStarts(device)?.let { if (it.isNotEmpty()) return it }
        val gptHeader = device.readSector(1)?.let { GptPartitionTable.parseHeader(it) }
        if (gptHeader != null) {
            val entryBytes = (gptHeader.partitionEntryCount * gptHeader.partitionEntrySize).coerceIn(0, 1 shl 20).toInt()
            val sectors = (entryBytes + device.sectorSize() - 1) / device.sectorSize()
            val data = device.readSectors(gptHeader.partitionEntryLba, sectors)
            if (data != null) {
                val parts = GptPartitionTable.parseEntries(data, gptHeader)
                val usable = parts.filter { !it.typeGuid.equals(EFI_SYSTEM_GUID, ignoreCase = true) }
                Log.i(TAG, "GPT: ${parts.size} partition(s), probing ${usable.size}")
                return usable.map { it.startLba }
            }
        }
        val mbrEntries = MbrPartitionTable.parse(mbr)
        if (mbrEntries != null) {
            return mbrEntries.filter { it.partitionType != 0 && it.partitionType != 0xEE && it.partitionType != 0xEF }.map { it.startLba }
        }
        return emptyList()
    }

    private fun tryApfs(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = ApfsReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "APFS at LBA $lba")
        // Experimental in-place writer: off unless the user enabled it (it is not copy-on-write).
        return Pair(reader, if (enableApfsWrite && !reader.hasEncryptedVolume()) ApfsWriter(device, lba) else null)
    }

    private fun tryHfsPlus(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = HfsPlusReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "HFS+ at LBA $lba")
        val sectorSize = device.sectorSize()
        val headerSector = lba + 2  // HFS+ VH is at byte 1024 = sector 2 for 512-byte sectors
        val sector = device.readSector(headerSector) ?: return Pair(reader, null)
        val header = HfsPlusVolumeHeaderParser.parse(sector) ?: return Pair(reader, null)
        return Pair(reader, HfsPlusWriter(device, lba, header))
    }

    private fun tryBitLocker(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = app.feldkit.storage.filesystem.bitlocker.BitLockerReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "BitLocker at LBA $lba")
        return Pair(reader, null)   // read-only
    }

    /** Mounts the filesystem inside an unlocked container (starts at sector 0 of [device]); never a nested BitLocker. */
    fun mountInner(device: BlockDeviceReader): Pair<FileSystemReader, FileSystemWriter?>? {
        // BitLocker volumes stay read-only: nothing is re-encrypted, so the inner writers are dropped
        return tryNtfs(device, 0, allowWrite = false)?.let { it.first to null } ?: tryExFat(device, 0)?.let { it.first to null }
            ?: tryFat32(device, 0)?.let { it.first to null } ?: tryHfsPlus(device, 0)?.let { it.first to null }
    }

    private fun tryNtfs(device: BlockDeviceReader, lba: Long, allowWrite: Boolean = true): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = app.feldkit.storage.filesystem.ntfs.NtfsReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "NTFS at LBA $lba")
        // Experimental writer: off unless the user enabled it (Settings > Experimental); never for a BitLocker container
        return Pair(reader, if (enableNtfsWrite && allowWrite) app.feldkit.storage.filesystem.ntfs.NtfsWriter(reader) else null)
    }

    private fun tryFat32(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = Fat32Reader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "FAT32 at LBA $lba")
        val header = reader.getVolumeHeader() ?: return Pair(reader, null)
        return Pair(reader, Fat32Writer(device, header))
    }

    private fun tryExFat(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = ExFatReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "exFAT at LBA $lba")
        return Pair(reader, ExFatWriter(device, reader))
    }

    private fun tryUdf(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = app.feldkit.storage.filesystem.udf.UdfReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "UDF at LBA $lba")
        return Pair(reader, null)
    }

    private fun tryIso(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = app.feldkit.storage.filesystem.iso.Iso9660Reader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "ISO 9660 at LBA $lba")
        return Pair(reader, null)   // optical images are read-only
    }

    private fun tryExt(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = ExtReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "ext at LBA $lba")
        return Pair(reader, null) // ext write not implemented
    }
}
