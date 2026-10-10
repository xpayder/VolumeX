package app.feldkit.storage.disk

import app.feldkit.data.models.UsbDeviceInfo

class DiskScanner {

    fun scan(
        devices: List<UsbDeviceInfo>
    ): List<UsbDeviceInfo> {
        return devices.filter {
            isStorageDevice(it)
        }
    }
    private fun isStorageDevice(
        device: UsbDeviceInfo
    ): Boolean {
        /*
         * Placeholder.
         * Later we'll inspect the USB Interface Class.
         */
        return true
    }

}