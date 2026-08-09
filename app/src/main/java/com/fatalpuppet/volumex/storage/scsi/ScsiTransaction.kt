package com.fatalpuppet.volumex.storage.scsi

data class ScsiTransaction(

    val command: String,

    val success: Boolean,

    val elapsedMs: Long,

    val message: String,

    val data: ByteArray? = null,

    val timestamp: Long = System.currentTimeMillis()
)