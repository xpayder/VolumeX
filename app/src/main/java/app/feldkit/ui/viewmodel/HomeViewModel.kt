package app.feldkit.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.content.Context
import android.hardware.usb.UsbDevice
import app.feldkit.data.usb.UsbState
import app.feldkit.ui.state.HomeUiState
import app.feldkit.data.repository.UsbRepository

class HomeViewModel : ViewModel() {
    var uiState by mutableStateOf(HomeUiState())
        private set
    private var usbRepository: UsbRepository? = null

    fun scanUsbDevices() {
        val repository = usbRepository ?: return
        val devices = repository.devices()

        uiState = uiState.copy(
            connectedDevices = devices
        )
        if (devices.isEmpty()) {
            updateUsbState(
                UsbState.WAITING,
                "Waiting for USB device"
            )
        } else {
            updateUsbState(
                UsbState.CONNECTED,
                "${devices.size} USB device(s) connected"
            )
        }
    }
        fun onUsbChanged() {
        scanUsbDevices()
    }

    fun onUsbAttached() {
        scanUsbDevices()
        updateUsbState(
            UsbState.CONNECTED,
            "USB device connected"
        )
    }
    fun connectFirstDevice(): Boolean {
        val repository = usbRepository ?: return false
        scanUsbDevices()
        if (uiState.connectedDevices.isEmpty())
            return false
        return repository.openFirstDevice()
    }
    fun onUsbDetached() {
        scanUsbDevices()
        updateUsbState(
            UsbState.WAITING,
            "Waiting for USB device"
        )
    }
    fun updateUsbState(
        state: UsbState,
        message: String
    ) {
        uiState = uiState.copy(
            usbState = state,
            statusMessage = message
        )
    }
    fun initialize(context: Context) {
        if (usbRepository != null) return
        usbRepository = UsbRepository(context)
        usbRepository?.registerReceiver(
            onAttach = ::onUsbAttached,
            onDetach = ::onUsbDetached,
            onPermissionGranted = ::onUsbPermissionGranted
        )
    }
    fun onUsbPermissionGranted(
        device: UsbDevice
    ) {
        val repository = usbRepository ?: return

        if (repository.openDeviceAfterPermission(device)) {
            updateUsbState(
                UsbState.CONNECTED,
                "USB drive opened"
            )
        } else {
            updateUsbState(
                UsbState.ERROR,
                "USB drive could not be opened"
            )
        }
    }

    fun readFirstSector() {
        val repository =
            usbRepository
                ?: return
        val sector =
            repository.readSectorZero()
        if (sector == null) {
            updateUsbState(
                UsbState.ERROR,
                "Unable to read sector 0"
            )
            return
        }
        updateUsbState(
            UsbState.CONNECTED,
            "Sector 0 read (${sector.size} bytes)"
        )
    }

    fun checkUsbSupport() {
        val repository = usbRepository ?: return
        if (repository.isUsbSupported()) {
            updateUsbState(
                UsbState.WAITING,
                "USB Host supported"
            )
        } else {
            updateUsbState(
                UsbState.ERROR,
                "USB Host not supported"
            )
        }
    }
    fun refreshUsbStatus() {
        val repository = usbRepository ?: return

        if (repository.hasDevices()) {
            updateUsbState(
                UsbState.CONNECTED,
                "${repository.deviceCount()} USB device(s) detected"
            )
        } else {
            updateUsbState(
                UsbState.WAITING,
                "Waiting for USB device"
            )
        }
    }
}