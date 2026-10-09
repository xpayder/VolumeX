package com.fatalpuppet.volumex.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.MimeTypeMap
import com.fatalpuppet.volumex.storage.ActiveDriveSession

/**
 * ContentProvider that streams file data directly from the USB drive without
 * copying to internal storage.
 *
 * URI format:
 *   content://com.fatalpuppet.volumex.drivefile/file/<inodeOid>?path=<filePath>
 *
 * The <inodeOid> is used to locate the file in the APFS inode tree.
 * The optional query parameter `path` is used to determine the MIME type.
 *
 * Other apps (Gallery, VLC, etc.) can open files by receiving this URI via
 * Intent.ACTION_VIEW or Intent.ACTION_SEND.
 */
class DriveFileProvider : ContentProvider() {

    companion object {
        private const val TAG = "VolumeX"
        const val AUTHORITY = "com.fatalpuppet.volumex.drivefile"

        fun buildUri(inodeOid: Long, filePath: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendPath(inodeOid.toString())
                .appendQueryParameter("path", filePath)
                .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val path = uri.getQueryParameter("path") ?: return "*/*"
        val ext = path.substringAfterLast('.').lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private val proxyThread by lazy { android.os.HandlerThread("vx-proxy-fd").also { it.start() } }
    private val proxyHandler by lazy { android.os.Handler(proxyThread.looper) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val inodeOid = uri.lastPathSegment?.toLongOrNull() ?: run {
            Log.e(TAG, "DriveFileProvider: invalid URI, no inodeOid: $uri"); return null
        }
        val fsReader = ActiveDriveSession.reader ?: run { Log.e(TAG, "DriveFileProvider: no active drive session"); return null }
        val volIndex = ActiveDriveSession.currentVolumeIndex
        val filePath = uri.getQueryParameter("path") ?: ""
        val entry = findEntryByOid(fsReader, volIndex, inodeOid, filePath) ?: run {
            Log.e(TAG, "DriveFileProvider: entry not found for oid=$inodeOid path=$filePath"); return null
        }
        val sm = context?.getSystemService(android.os.storage.StorageManager::class.java) ?: return null
        // A real random-access descriptor: players can seek, and nothing is buffered in memory.
        val callback = object : android.os.ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = entry.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                val n = fsReader.readRange(entry, offset, data, 0, size)
                if (n < 0) throw android.system.ErrnoException("onRead", android.system.OsConstants.EIO)
                return n
            }
            override fun onRelease() {}
        }
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, proxyHandler)
    }

    private fun findEntryByOid(
        reader: com.fatalpuppet.volumex.storage.filesystem.FileSystemReader,
        volumeIndex: Int,
        inodeOid: Long,
        filePath: String
    ): com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry? {
        // If we have the path, use it directly
        if (filePath.isNotEmpty()) {
            val parentPath = filePath.substringBeforeLast('/', "/").let {
                if (it.isEmpty()) "/" else it
            }
            val entries = reader.listDirectory(volumeIndex, parentPath)
            val found = entries.find { it.inodeOid == inodeOid || it.path == filePath }
            if (found != null) return found
        }
        // Fallback: search from root (limited depth)
        return searchByOid(reader, volumeIndex, "/", inodeOid, depth = 0, maxDepth = 8)
    }

    private fun searchByOid(
        reader: com.fatalpuppet.volumex.storage.filesystem.FileSystemReader,
        volumeIndex: Int,
        path: String,
        targetOid: Long,
        depth: Int,
        maxDepth: Int
    ): com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry? {
        if (depth > maxDepth) return null
        val entries = try { reader.listDirectory(volumeIndex, path) } catch (e: Exception) { emptyList() }
        for (e in entries) {
            if (e.inodeOid == targetOid) return e
            if (e.isDirectory) {
                val found = searchByOid(reader, volumeIndex, e.path, targetOid, depth + 1, maxDepth)
                if (found != null) return found
            }
        }
        return null
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
