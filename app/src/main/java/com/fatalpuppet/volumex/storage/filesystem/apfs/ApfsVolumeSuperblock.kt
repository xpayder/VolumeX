// file: app/src/main/java/com/fatalpuppet/volumex/storage/filesystem/apfs/ApfsVolumeSuperblock.kt
package com.fatalpuppet.volumex.storage.filesystem.apfs

data class ApfsVolumeSuperblock(
    val volumeName: String,
    val volumeBlockSize: Long,
    val objectCount: Long,
    val rootDirectoryObjectId: Long,
    val extentCount: Long
)