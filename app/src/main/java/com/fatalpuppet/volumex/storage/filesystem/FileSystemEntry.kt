package com.fatalpuppet.volumex.storage.filesystem

import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsExtent

enum class FileType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, OTHER, DIRECTORY
}

data class FileSystemEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val createdAt: Long,    // milliseconds since epoch
    val modifiedAt: Long,   // milliseconds since epoch
    // APFS-specific
    val inodeOid: Long = 0L,
    val parentOid: Long = 0L,
    val extents: List<ApfsExtent> = emptyList(),
    // HFS+-specific
    val hfsCatalogId: Int = 0,
    val hfsParentId: Int = 0,
    // Folder details (populated on demand)
    val childCount: Int? = null,
    val totalSize: Long? = null,
    // exFAT: data is stored contiguously (NoFatChain flag) and the allocated length of the stream
    val contiguous: Boolean = false,
    // which mounted partition the entry belongs to (multi-partition drives)
    val partitionId: Int = 0,
    val allocLength: Long = 0L
) {
    val extension: String get() {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    val formattedSize: String get() = formatBytes(size)

    val fileType: FileType get() = when {
        isDirectory -> FileType.DIRECTORY
        extension in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "svg") -> FileType.IMAGE
        extension in setOf("mp4", "mov", "avi", "mkv", "m4v", "wmv", "flv", "webm", "3gp") -> FileType.VIDEO
        extension in setOf("mp3", "aac", "flac", "wav", "m4a", "ogg", "opus", "wma") -> FileType.AUDIO
        extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "md") -> FileType.DOCUMENT
        extension in setOf("zip", "gz", "tar", "7z", "rar", "bz2", "xz", "dmg", "pkg", "iso") -> FileType.ARCHIVE
        extension in setOf("kt", "java", "py", "js", "ts", "swift", "c", "cpp", "h", "rs", "go", "sh", "json", "xml", "yaml", "toml") -> FileType.CODE
        else -> FileType.OTHER
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
