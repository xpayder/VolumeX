package app.feldkit.data.models

data class UsbDeviceInfo(
    val deviceName: String,
    val manufacturer: String?,
    val productName: String?,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
    val permissionGranted: Boolean
)