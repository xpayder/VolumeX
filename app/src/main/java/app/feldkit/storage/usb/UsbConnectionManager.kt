package app.feldkit.storage.usb

import android.hardware.usb.*

class UsbConnectionManager(
    private val usbManager: UsbManager
) {
    fun createReader(
        device: UsbDevice
    ): UsbBlockDeviceReader {
        return UsbBlockDeviceReader(
            usbManager,
            device
        )
    }
}