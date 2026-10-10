package app.feldkit.storage.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

data class UsbUasInterface(
    val usbInterface: UsbInterface,
    val commandOut: UsbEndpoint,
    val statusIn: UsbEndpoint,
    val dataIn: UsbEndpoint,
    val dataOut: UsbEndpoint,
    val interfaceNumber: Int
)