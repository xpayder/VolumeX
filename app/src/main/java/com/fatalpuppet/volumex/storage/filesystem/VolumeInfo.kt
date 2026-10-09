package com.fatalpuppet.volumex.storage.filesystem

data class VolumeInfo(
    val name: String,
    val type: String,           // "APFS", "HFS+", "exFAT", etc.
    val uuid: String = "",
    val totalBlocks: Long = 0L,
    val blockSize: Long = 0L,
    val freeBlocks: Long = 0L,
    val isEncrypted: Boolean = false,
    val numFiles: Long = 0L,
    val numDirectories: Long = 0L,
    /** true when [freeBlocks] is an actual measurement (0 free is then a real value). */
    val freeKnown: Boolean = false
) {
    val usedFraction: Float get() = if (freeKnown && totalBlocks > 0) ((totalBlocks - freeBlocks).toFloat() / totalBlocks).coerceIn(0f, 1f) else 0f
    val usedSize: Long get() = if (freeKnown) (totalBlocks - freeBlocks).coerceAtLeast(0) * blockSize else 0L
    val formattedUsedSize: String get() = formatBytes(usedSize)
    val totalSize: Long get() = totalBlocks * blockSize
    val freeSize: Long get() = freeBlocks * blockSize

    val formattedTotalSize: String get() = formatBytes(totalSize)
    val formattedFreeSize: String get() = formatBytes(freeSize)

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "Unknown"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        bytes < 1024L * 1024 * 1024 * 1024 -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024 * 1024))} TB"
    }
}
