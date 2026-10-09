package com.fatalpuppet.volumex.storage.filesystem

interface FileSystemWriter {
    fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean
    fun deleteEntry(entry: FileSystemEntry): Boolean
    fun renameEntry(entry: FileSystemEntry, newName: String): Boolean
    fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean

    /**
     * Write a file of [size] bytes read from [input], reporting bytes written via [onProgress].
     * Implementations should stream; the default buffers the whole file in memory.
     */
    fun writeFileStream(
        parentEntry: FileSystemEntry, name: String, size: Long,
        input: java.io.InputStream, onProgress: ((Long) -> Unit)? = null
    ): Boolean = writeFile(parentEntry, name, input.readBytes())
}
