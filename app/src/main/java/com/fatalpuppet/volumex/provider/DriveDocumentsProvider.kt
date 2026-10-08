package com.fatalpuppet.volumex.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.fatalpuppet.volumex.R
import com.fatalpuppet.volumex.storage.ActiveDriveSession
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry

/**
 * DocumentsProvider that exposes the mounted USB drive in the standard Android file picker
 * (ACTION_OPEN_DOCUMENT, ACTION_GET_CONTENT, etc.).
 *
 * Document IDs use the format: "vol_<volumeIndex>/<path>"
 * e.g., "vol_0/", "vol_0/Documents/file.pdf"
 */
class DriveDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "VolumeX"
        const val AUTHORITY = "com.fatalpuppet.volumex.documents"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_SUMMARY
        )

        val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val session = ActiveDriveSession.reader ?: return result
        val volumes = ActiveDriveSession.volumes

        volumes.forEachIndexed { i, vol ->
            result.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, "vol_$i")
                add(DocumentsContract.Root.COLUMN_TITLE, vol.name.ifEmpty { "Volume ${i + 1}" })
                add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                            DocumentsContract.Root.FLAG_LOCAL_ONLY
                )
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "vol_$i/")
                add(DocumentsContract.Root.COLUMN_SUMMARY, vol.type)
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val (volIndex, path) = parseDocId(documentId) ?: return result
        val reader = ActiveDriveSession.reader ?: return result

        if (path == "/" || path.isEmpty()) {
            // Root directory row
            val vol = ActiveDriveSession.volumes.getOrNull(volIndex)
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, vol?.name ?: "Volume ${volIndex + 1}")
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            }
            return result
        }

        // Find the entry in its parent directory
        val parentPath = path.substringBeforeLast('/', "/").let { if (it.isEmpty()) "/" else it }
        val name = path.substringAfterLast('/')
        val entries = try { reader.listDirectory(volIndex, parentPath) } catch (e: Exception) { emptyList() }
        val entry = entries.find { it.name == name } ?: return result
        addEntryRow(result, entry, volIndex)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val (volIndex, path) = parseDocId(parentDocumentId) ?: return result
        val reader = ActiveDriveSession.reader ?: return result

        val listPath = if (path.isEmpty()) "/" else path
        val entries = try { reader.listDirectory(volIndex, listPath) } catch (e: Exception) { emptyList() }
        for (entry in entries) {
            addEntryRow(result, entry, volIndex)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val (volIndex, path) = parseDocId(documentId) ?: throw IllegalArgumentException("Invalid documentId: $documentId")
        val reader = ActiveDriveSession.reader ?: throw IllegalStateException("No active drive session")

        // Find the entry
        val parentPath = path.substringBeforeLast('/', "/").let { if (it.isEmpty()) "/" else it }
        val name = path.substringAfterLast('/')
        val entries = reader.listDirectory(volIndex, parentPath)
        val entry = entries.find { it.name == name }
            ?: throw IllegalArgumentException("File not found: $path")

        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()

        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    val data = reader.readFile(entry)
                    if (data != null) out.write(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "DriveDocumentsProvider: error streaming document", e)
                try { writeEnd.close() } catch (_: Exception) {}
            }
        }.start()

        return readEnd
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val volIndex = rootId.removePrefix("vol_").toIntOrNull() ?: return result
        val reader = ActiveDriveSession.reader ?: return result
        val matches = try { reader.searchFiles(query, volIndex) } catch (e: Exception) { emptyList() }
        for (entry in matches) addEntryRow(result, entry, volIndex)
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parse "vol_<idx>/<path>" → Pair(volIndex, path) */
    private fun parseDocId(documentId: String): Pair<Int, String>? {
        val slash = documentId.indexOf('/')
        if (slash < 0) return null
        val prefix = documentId.substring(0, slash)
        val volIndex = prefix.removePrefix("vol_").toIntOrNull() ?: return null
        val path = documentId.substring(slash) // includes leading "/"
        return Pair(volIndex, path)
    }

    private fun addEntryRow(cursor: MatrixCursor, entry: FileSystemEntry, volIndex: Int) {
        val docId = "vol_$volIndex${entry.path}"
        val mime = if (entry.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            val ext = entry.extension
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entry.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            add(DocumentsContract.Document.COLUMN_SIZE, if (entry.isDirectory) null else entry.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, entry.modifiedAt)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }
}
