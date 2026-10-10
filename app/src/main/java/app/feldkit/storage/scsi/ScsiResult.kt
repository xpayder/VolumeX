package app.feldkit.storage.scsi

data class ScsiResult(
    val success: Boolean,
    val data: ByteArray?,
    val message: String
)
