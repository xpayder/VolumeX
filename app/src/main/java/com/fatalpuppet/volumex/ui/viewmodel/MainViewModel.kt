package com.fatalpuppet.volumex.ui.viewmodel

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fatalpuppet.volumex.storage.ActiveDriveSession
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsReader
import com.fatalpuppet.volumex.storage.filesystem.fat32.Fat32Reader
import com.fatalpuppet.volumex.storage.filesystem.ext.ExtReader
import com.fatalpuppet.volumex.storage.filesystem.hfsplus.HfsPlusReader
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
        val reader: FileSystemReader
    ) : DeviceState()
    data class Error(val message: String) : DeviceState()
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

                    // Try to detect filesystem and mount
                    val fsReader = tryMountFilesystem(reader)
                    if (fsReader == null) {
                        _deviceState.value = DeviceState.Error("No supported filesystem found (expected APFS or HFS+)")
                        _statusMessage.value = "Unsupported filesystem"
                        return@withContext
                    }

                    val volumes = fsReader.getVolumeInfos()
                    // Update the global session singleton so providers can access it
                    ActiveDriveSession.reader = fsReader
                    ActiveDriveSession.volumes = volumes
                    _deviceState.value = DeviceState.Connected(
                        deviceName = device.productName ?: "USB Drive",
                        volumes = volumes,
                        reader = fsReader
                    )
                    _statusMessage.value = "Connected: ${volumes.size} volume(s) found"
                    Log.i(TAG, "Mounted filesystem with ${volumes.size} volume(s)")

                } catch (e: Exception) {
                    Log.e(TAG, "Error connecting device", e)
                    _deviceState.value = DeviceState.Error("Error: ${e.message}")
                    _statusMessage.value = "Connection error"
                }
            }
        }
    }

    private suspend fun tryMountFilesystem(reader: UsbBlockDeviceReader): FileSystemReader? {
        return withContext(Dispatchers.IO) {
            // Try reading GPT/MBR to find partition
            // For simplicity, probe partition at LBA 0 (raw) and common GPT offsets
            val probeOffsets = listOf(0L, 40L, 56L, 64L, 128L, 2048L)

            for (lba in probeOffsets) {
                // Try APFS
                val apfsReader = ApfsReader(reader, lba)
                if (apfsReader.mount()) {
                    Log.i(TAG, "APFS filesystem found at partition LBA $lba")
                    return@withContext apfsReader
                }

                // Try HFS+
                val hfsReader = HfsPlusReader(reader, lba)
                if (hfsReader.mount()) {
                    Log.i(TAG, "HFS+ filesystem found at partition LBA $lba")
                    return@withContext hfsReader
                }

                // Try FAT32
                val fatReader = Fat32Reader(reader, lba)
                if (fatReader.mount()) {
                    Log.i(TAG, "FAT32 filesystem found at partition LBA $lba")
                    return@withContext fatReader
                }

                // Try ext2/3/4
                val extReader = ExtReader(reader, lba)
                if (extReader.mount()) {
                    Log.i(TAG, "ext filesystem found at partition LBA $lba")
                    return@withContext extReader
                }
            }
            null
        }
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
