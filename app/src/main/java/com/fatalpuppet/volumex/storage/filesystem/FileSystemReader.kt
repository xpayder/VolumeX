package com.fatalpuppet.volumex.storage.filesystem

interface FileSystemReader {
    /** Initialize and validate the filesystem. Returns true on success. */
    fun mount(): Boolean

    /** List available volumes/partitions. */
    fun getVolumeInfos(): List<VolumeInfo>

    /** List entries in the given directory path for the given volume index. */
    fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry>

    /** Read the full content of a file entry into a ByteArray. */
    fun readFile(entry: FileSystemEntry): ByteArray?

    /** Release resources. */
    fun unmount()
}
