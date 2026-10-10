package app.feldkit.ui.state

//import app.feldkit.data.model.UsbDeviceInfo
import app.feldkit.data.usb.UsbState
import app.feldkit.data.models.UsbDeviceInfo

data class HomeUiState(

    val usbState: UsbState = UsbState.WAITING,

    val statusMessage: String = "Waiting for USB device",

    val connectedDevice: UsbDeviceInfo? = null,

    val connectedDevices: List<UsbDeviceInfo> = emptyList()
)