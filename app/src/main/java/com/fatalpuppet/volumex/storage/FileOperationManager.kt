package com.fatalpuppet.volumex.storage

import android.content.Context
import android.os.Environment
import android.util.Log
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

sealed class TransferResult {
    data class Success(val bytesTransferred: Long) : TransferResult()
    data class Failure(val reason: String) : TransferResult()
    object Cancelled : TransferResult()
}

data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isComplete: Boolean = false,
    val error: String? = null
) {
    val progressFraction: Float get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
    val progressPercent: Int get() = (progressFraction * 100).toInt()
    val speedBps: Long = 0L

    val formattedProgress: String get() = "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

class FileOperationManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "VolumeX"
    }

    private var cancelled = false

    fun cancel() { cancelled = true }

    /**
     * Copy a file from the USB drive to Android Downloads folder.
     */
    suspend fun copyToAndroid(
        reader: FileSystemReader,
        entry: FileSystemEntry,
        onProgress: (TransferProgress) -> Unit = {}
    ): TransferResult = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            onProgress(TransferProgress(entry.name, 0, entry.size))

            if (cancelled) return@withContext TransferResult.Cancelled

            val data = reader.readFile(entry)
                ?: return@withContext TransferResult.Failure("Could not read file from drive")

            if (cancelled) return@withContext TransferResult.Cancelled

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()

            val destFile = File(downloadsDir, sanitizeFileName(entry.name))
            destFile.writeBytes(data)

            onProgress(TransferProgress(entry.name, data.size.toLong(), entry.size, isComplete = true))
            Log.i(TAG, "Copied ${entry.name} to ${destFile.absolutePath}")

            TransferResult.Success(data.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file to Android", e)
            TransferResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Copy a file from Android storage to the USB drive.
     */
    suspend fun copyFromAndroid(
        writer: FileSystemWriter,
        sourceFile: File,
        parentEntry: FileSystemEntry,
        onProgress: (TransferProgress) -> Unit = {}
    ): TransferResult = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val totalSize = sourceFile.length()
            onProgress(TransferProgress(sourceFile.name, 0, totalSize))

            if (cancelled) return@withContext TransferResult.Cancelled

            if (!sourceFile.exists() || !sourceFile.canRead()) {
                return@withContext TransferResult.Failure("Cannot read source file")
            }

            val data = FileInputStream(sourceFile).use { it.readBytes() }

            if (cancelled) return@withContext TransferResult.Cancelled

            val success = writer.writeFile(parentEntry, sourceFile.name, data)
            if (!success) {
                return@withContext TransferResult.Failure("Write to drive failed (write support not yet implemented for this filesystem)")
            }

            onProgress(TransferProgress(sourceFile.name, totalSize, totalSize, isComplete = true))
            TransferResult.Success(totalSize)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file from Android", e)
            TransferResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Create a directory on the USB drive.
     */
    suspend fun createDirectory(
        writer: FileSystemWriter,
        parentEntry: FileSystemEntry,
        name: String
    ): TransferResult = withContext(Dispatchers.IO) {
        try {
            if (writer.createDirectory(parentEntry, name)) {
                TransferResult.Success(0L)
            } else {
                TransferResult.Failure("Create directory failed (not implemented for this filesystem)")
            }
        } catch (e: Exception) {
            TransferResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Delete a file or directory from the USB drive.
     */
    suspend fun deleteEntry(
        writer: FileSystemWriter,
        entry: FileSystemEntry
    ): TransferResult = withContext(Dispatchers.IO) {
        try {
            if (writer.deleteEntry(entry)) {
                TransferResult.Success(0L)
            } else {
                TransferResult.Failure("Delete failed (not implemented for this filesystem)")
            }
        } catch (e: Exception) {
            TransferResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Rename a file or directory on the USB drive.
     */
    suspend fun renameEntry(
        writer: FileSystemWriter,
        entry: FileSystemEntry,
        newName: String
    ): TransferResult = withContext(Dispatchers.IO) {
        try {
            if (writer.renameEntry(entry, newName)) {
                TransferResult.Success(0L)
            } else {
                TransferResult.Failure("Rename failed (not implemented for this filesystem)")
            }
        } catch (e: Exception) {
            TransferResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}
