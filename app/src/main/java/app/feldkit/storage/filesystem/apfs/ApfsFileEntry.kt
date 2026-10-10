// file: app/src/main/java/app/feldkit/storage/filesystem/apfs/ApfsFileEntry.kt
package app.feldkit.storage.filesystem.apfs

data class ApfsFileEntry(
    val objectId: Long,
    val name: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val creationTime: Long,
    val modificationTime: Long
)