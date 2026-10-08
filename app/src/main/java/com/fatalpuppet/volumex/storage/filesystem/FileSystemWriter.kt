package com.fatalpuppet.volumex.storage.filesystem

interface FileSystemWriter {
    fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean
    fun deleteEntry(entry: FileSystemEntry): Boolean
    fun renameEntry(entry: FileSystemEntry, newName: String): Boolean
    fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean
}
