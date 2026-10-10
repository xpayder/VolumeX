package com.fatalpuppet.volumex.storage.filesystem

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsContainerSuperblockParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsBTreeParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsOMapParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsVolumeSuperblockParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsReader
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsWriter
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatReader
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatWriter
import com.fatalpuppet.volumex.storage.filesystem.fat32.Fat32Reader
import com.fatalpuppet.volumex.storage.filesystem.fat32.Fat32Writer
import com.fatalpuppet.volumex.storage.filesystem.ext.ExtReader
import com.fatalpuppet.volumex.storage.filesystem.hfsplus.HfsPlusReader
import com.fatalpuppet.volumex.storage.filesystem.hfsplus.HfsPlusWriter
import com.fatalpuppet.volumex.storage.filesystem.hfsplus.HfsPlusVolumeHeaderParser
import com.fatalpuppet.volumex.storage.filesystem.lvm.LvmBlockDevice
import com.fatalpuppet.volumex.storage.filesystem.lvm.LvmParser
import com.fatalpuppet.volumex.storage.filesystem.partition.GptPartitionTable
import com.fatalpuppet.volumex.storage.filesystem.partition.MbrPartitionTable

/** Detects and mounts a filesystem on a raw block device (extracted from MainViewModel so it is testable off-device). */
object FilesystemMounter {
    /** Opt-in switch for the experimental APFS writer (Settings > Experimental). */
    @Volatile var enableApfsWrite: Boolean = false
    private const val TAG = "VolumeX"

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
                ?: tryExFat(device, lba) ?: tryExt(device, lba)
            if (m != null) out.add(MountedPartition(m.first, m.second, lba))
        }
        return out
    }

    private const val EFI_SYSTEM_GUID = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b"

    /** Partition start LBAs from the GPT (preferred) or the MBR; empty if the media is unpartitioned. */
    private fun partitionStarts(device: BlockDeviceReader): List<Long> {
        val mbr = device.readSector(0) ?: return emptyList()
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
        val reader = com.fatalpuppet.volumex.storage.filesystem.bitlocker.BitLockerReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "BitLocker at LBA $lba")
        return Pair(reader, null)   // read-only
    }

    /** Mounts the filesystem inside an unlocked container (starts at sector 0 of [device]); never a nested BitLocker. */
    fun mountInner(device: BlockDeviceReader): Pair<FileSystemReader, FileSystemWriter?>? =
        tryNtfs(device, 0) ?: tryExFat(device, 0)?.let { it.first to null } ?: tryFat32(device, 0)?.let { it.first to null } ?: tryHfsPlus(device, 0)?.let { it.first to null }

    private fun tryNtfs(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = com.fatalpuppet.volumex.storage.filesystem.ntfs.NtfsReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "NTFS at LBA $lba")
        return Pair(reader, null)   // read-only
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

    private fun tryExt(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = ExtReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "ext at LBA $lba")
        return Pair(reader, null) // ext write not implemented
    }
}
