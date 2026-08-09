package com.fatalpuppet.volumex.storage.scsi

data class ScsiResult(
    val success: Boolean,
    val data: ByteArray?,
    val message: String
)
