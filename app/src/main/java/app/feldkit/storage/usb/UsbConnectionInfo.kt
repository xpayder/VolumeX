package app.feldkit.storage.usb

data class UsbConnectionInfo(

    val vendorId: Int,

    val productId: Int,

    val manufacturer: String?,

    val product: String?,

    val interfaceNumber: Int,

    val endpointIn: Int,

    val endpointOut: Int

)