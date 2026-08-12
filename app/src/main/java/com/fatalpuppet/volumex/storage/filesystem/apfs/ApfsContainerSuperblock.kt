package com.fatalpuppet.volumex.storage.filesystem.apfs

data class ApfsContainerSuperblock(
    val blockSize: Long,
    val blockCount: Long,
    val containerUuid: String,
    val nextObjectId: Long,
    val nextTransactionId: Long,
    val volumeCount: Int
)