package app.feldkit.storage.disk

import app.feldkit.storage.usb.DeviceConnectionState

data class DiskInfo(

    val name: String,

    val vendorId: Int,

    val productId: Int,

    val capacity: Long? = null,

    val partitionCount: Int = 0,

    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED

)