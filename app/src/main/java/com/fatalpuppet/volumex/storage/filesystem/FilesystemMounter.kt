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

/** Detects and mounts a filesystem on a raw block device (extracted from MainViewModel so it is testable off-device). */
object FilesystemMounter {
    private const val TAG = "VolumeX"

    fun mount(device: BlockDeviceReader): Pair<FileSystemReader, FileSystemWriter?>? {
        // Check for LVM
        val effectiveDevice: BlockDeviceReader = if (LvmParser.detect(device)) {
            val pv = LvmParser.parsePV(device) ?: return null
            val meta = LvmParser.readVgMetadata(device, pv) ?: return null
            val extSize = LvmParser.parseExtentSize(meta)
            val lvs = LvmParser.parseLogicalVolumes(meta)
            if (lvs.isEmpty()) return null
            Log.i(TAG, "LVM: found ${lvs.size} logical volume(s), using first: ${lvs[0].name}")
            LvmBlockDevice(device, lvs[0], extSize)
        } else {
            device
        }

        val probeOffsets = listOf(0L, 40L, 56L, 64L, 128L, 2048L)
        for (lba in probeOffsets) {
            tryApfs(effectiveDevice, lba)?.let { return it }
            tryHfsPlus(effectiveDevice, lba)?.let { return it }
            tryFat32(effectiveDevice, lba)?.let { return it }
            tryExFat(effectiveDevice, lba)?.let { return it }
            tryExt(effectiveDevice, lba)?.let { return it }
        }
        return null
    }

    private fun tryApfs(device: BlockDeviceReader, lba: Long): Pair<FileSystemReader, FileSystemWriter?>? {
        val reader = ApfsReader(device, lba)
        if (!reader.mount()) return null
        Log.i(TAG, "APFS at LBA $lba")
        // Read-only: ApfsWriter has not been validated against macOS (fsck_apfs) and must not touch real drives.
        return Pair(reader, null)
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
