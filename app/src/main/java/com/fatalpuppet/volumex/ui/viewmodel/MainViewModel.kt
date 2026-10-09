package com.fatalpuppet.volumex.ui.viewmodel

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fatalpuppet.volumex.storage.ActiveDriveSession
import com.fatalpuppet.volumex.storage.crypto.LuksDecryptor
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
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
import com.fatalpuppet.volumex.storage.usb.UsbBlockDeviceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class DeviceState {
    object Disconnected : DeviceState()
    object Connecting : DeviceState()
    data class Connected(
        val deviceName: String,
        val volumes: List<VolumeInfo>,
        val reader: FileSystemReader,
        val writer: FileSystemWriter? = null
    ) : DeviceState()
    data class Error(val message: String) : DeviceState()
    data class NeedsPassphrase(val hint: String) : DeviceState()
}

class MainViewModel : ViewModel() {
    companion object {
        private const val TAG = "VolumeX"
    }

    private val _deviceState = MutableStateFlow<DeviceState>(DeviceState.Disconnected)
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var activeReader: UsbBlockDeviceReader? = null
    private var pendingLuksDevice: UsbBlockDeviceReader? = null

    fun connectDevice(context: Context, device: UsbDevice) {
        viewModelScope.launch {
            _deviceState.value = DeviceState.Connecting
            _statusMessage.value = "Connecting to ${device.productName ?: "USB device"}..."

            withContext(Dispatchers.IO) {
                try {
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    val reader = UsbBlockDeviceReader(usbManager, device)

                    if (!reader.open()) {
                        _deviceState.value = DeviceState.Error("Failed to open USB device")
                        _statusMessage.value = "Connection failed"
                        return@withContext
                    }

                    activeReader = reader

                    // Check for LUKS encryption first
                    val luksDecryptor = LuksDecryptor(reader)
                    if (luksDecryptor.detect() > 0) {
                        pendingLuksDevice = reader
                        _deviceState.value = DeviceState.NeedsPassphrase("LUKS encrypted volume")
                        _statusMessage.value = "Encrypted volume – enter passphrase"
                        return@withContext
                    }

                    mountWithDevice(reader, device.productName ?: "USB Drive")

                } catch (e: Exception) {
                    Log.e(TAG, "Error connecting device", e)
                    _deviceState.value = DeviceState.Error("Error: ${e.message}")
                    _statusMessage.value = "Connection error"
                }
            }
        }
    }

    fun unlockLuks(passphrase: String) {
        val rawDevice = pendingLuksDevice ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val decryptor = LuksDecryptor(rawDevice)
                val decrypted = decryptor.unlock(passphrase)
                if (decrypted == null) {
                    _deviceState.value = DeviceState.Error("Wrong passphrase or unsupported LUKS format")
                    _statusMessage.value = "Decryption failed"
                } else {
                    pendingLuksDevice = null
                    mountWithDevice(decrypted, "Encrypted USB Drive")
                }
            }
        }
    }

    private fun mountWithDevice(device: BlockDeviceReader, deviceName: String) {
        val result = tryMountFilesystem(device)
        if (result == null) {
            _deviceState.value = DeviceState.Error("No supported filesystem found")
            _statusMessage.value = "Unsupported filesystem"
            return
        }
        val (fsReader, fsWriter) = result
        val volumes = fsReader.getVolumeInfos()
        ActiveDriveSession.reader = fsReader
        ActiveDriveSession.writer = fsWriter
        ActiveDriveSession.volumes = volumes
        _deviceState.value = DeviceState.Connected(
            deviceName = deviceName,
            volumes = volumes,
            reader = fsReader,
            writer = fsWriter
        )
        _statusMessage.value = "Connected: ${volumes.size} volume(s) found${if (fsWriter != null) " (writable)" else " (read-only)"}"
        Log.i(TAG, "Mounted filesystem with ${volumes.size} volume(s) writable=${fsWriter != null}")
    }

    private fun tryMountFilesystem(device: BlockDeviceReader): Pair<FileSystemReader, FileSystemWriter?>? {
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
        val btree = ApfsBTreeParser(device, lba, 4096L)
        val block0 = btree.readBlock(0) ?: return Pair(reader, null)
        val csb = ApfsContainerSuperblockParser.parse(block0) ?: return Pair(reader, null)
        val omap = btree.readBlock(csb.omapOid)?.let { ApfsOMapParser.parse(it) } ?: return Pair(reader, null)
        val volPaddr = btree.omapLookup(omap.treeOid, csb.fsOids.firstOrNull { it != 0L } ?: 0L) ?: return Pair(reader, null)
        val volData = btree.readBlock(volPaddr) ?: return Pair(reader, null)
        val volSb = ApfsVolumeSuperblockParser.parse(volData) ?: return Pair(reader, null)
        val btree2 = ApfsBTreeParser(device, lba, csb.blockSize)
        return Pair(reader, ApfsWriter(device, lba, csb, volSb, btree2))
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

    fun disconnectDevice() {
        activeReader?.close()
        activeReader = null
        (_deviceState.value as? DeviceState.Connected)?.reader?.unmount()
        ActiveDriveSession.clear()
        _deviceState.value = DeviceState.Disconnected
        _statusMessage.value = "Disconnected"
    }

    override fun onCleared() {
        super.onCleared()
        disconnectDevice()
    }
}
