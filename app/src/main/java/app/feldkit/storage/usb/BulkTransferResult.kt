package app.feldkit.storage.usb

data class BulkTransferResult(

    val success: Boolean,

    val bytesTransferred: Int,

    val data: ByteArray?

)