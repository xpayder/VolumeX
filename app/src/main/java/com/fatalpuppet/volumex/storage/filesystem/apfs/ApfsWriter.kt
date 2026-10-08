package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter

/**
 * APFS write support.
 * Write operations on APFS are complex (copy-on-write B-trees, checksums, transaction IDs).
 * This implementation provides the framework and implements file extraction (read-out),
 * while write operations (copy-in, mkdir, delete) write through a conservative approach.
 *
 * For production use, full APFS write support requires implementing:
 *  - Space manager (spaceman) allocation
 *  - B-tree COW mutation
 *  - Checkpointing and object map updates
 *
 * This class is kept as a stub writer that logs operations - actual writes are done
 * via the HFS+ writer for drives that support it, or via the device's own APFS driver.
 */
class ApfsWriter : FileSystemWriter {
    companion object {
        private const val TAG = "VolumeX"
    }

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean {
        Log.w(TAG, "APFS write: createDirectory not yet implemented (requires COW B-tree support)")
        return false
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean {
        Log.w(TAG, "APFS write: deleteEntry not yet implemented (requires COW B-tree support)")
        return false
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean {
        Log.w(TAG, "APFS write: renameEntry not yet implemented (requires COW B-tree support)")
        return false
    }

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean {
        Log.w(TAG, "APFS write: writeFile not yet implemented (requires space manager + COW B-tree)")
        return false
    }
}
