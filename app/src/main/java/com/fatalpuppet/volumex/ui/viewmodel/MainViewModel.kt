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
import com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter
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
                    // The attach broadcast can arrive while a USB 3 drive is still
                    // re-enumerating, so the first transfers fail. Let it settle and
                    // retry, re-resolving the device each time (its address may change).
                    var reader: UsbBlockDeviceReader? = null
                    for (attempt in 1..3) {
                        kotlinx.coroutines.delay(if (attempt == 1) 1000L else 2000L)
                        val current = usbManager.deviceList.values.firstOrNull {
                            it.vendorId == device.vendorId && it.productId == device.productId
                        } ?: break
                        if (!usbManager.hasPermission(current)) break
                        val candidate = UsbBlockDeviceReader(usbManager, current)
                        if (candidate.open()) { reader = candidate; break }
                        Log.w(TAG, "open() attempt $attempt failed")
                        candidate.close()
                    }

                    if (reader == null) {
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
        val result = FilesystemMounter.mount(device)
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
