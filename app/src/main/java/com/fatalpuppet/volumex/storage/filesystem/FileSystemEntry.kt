package com.fatalpuppet.volumex.storage.filesystem

import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsExtent

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
    val hfsParentId: Int = 0
) {
    val extension: String get() {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    val formattedSize: String get() = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(size / (1024.0 * 1024 * 1024))} GB"
    }
}
