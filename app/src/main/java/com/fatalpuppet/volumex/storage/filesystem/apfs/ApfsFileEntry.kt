// file: app/src/main/java/com/fatalpuppet/volumex/storage/filesystem/apfs/ApfsFileEntry.kt
package com.fatalpuppet.volumex.storage.filesystem.apfs

data class ApfsFileEntry(
    val objectId: Long,
    val name: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val creationTime: Long,
    val modificationTime: Long
)