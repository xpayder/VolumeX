package app.feldkit.offload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** A place the offload writes to: a plain folder or a Storage Access Framework tree. */
interface Destination {
    val label: String
    /** Creates the nested folders (if needed) and returns a handle to the innermost one. [path] is relative to the destination's root. */
    fun dir(path: List<String>): DestDir
}

interface DestDir {
    /** Size of an existing file with that name, or null. */
    fun sizeOf(name: String): Long?
    /** Starts a new file. If the name is taken, the sink uses a free variant ("name (1).ext"); see [DestSink.name]. */
    fun create(name: String): DestSink
    fun open(name: String): InputStream?
    fun delete(name: String)
}

interface DestSink {
    /** The name actually used. */
    val name: String
    fun write(buf: ByteArray, off: Int, len: Int)
    /** Flushes to the storage hardware before returning, so a read-back sees what really landed. */
    fun finish()
    fun abort()
}

internal fun freeName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.'); val stem = if (dot > 0) name.substring(0, dot) else name; val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (taken("$stem ($n)$ext")) n++
    return "$stem ($n)$ext"
}

/** A folder on the phone's shared storage (e.g. Downloads/FeldKit). */
class FileDestination(private val root: File, override val label: String = root.name) : Destination {
    override fun dir(path: List<String>): DestDir {
        var d = root
        for (p in path) d = File(d, p)
        d.mkdirs()
        return FileDir(d)
    }

    private class FileDir(val dir: File) : DestDir {
        override fun sizeOf(name: String): Long? = File(dir, name).takeIf { it.isFile }?.length()
        override fun create(name: String): DestSink {
            val real = freeName(name) { File(dir, it).exists() }
            val f = File(dir, real)
            val out = FileOutputStream(f)
            return object : DestSink {
                override val name = real
                override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
                override fun finish() { out.flush(); out.fd.sync(); out.close() }
                override fun abort() { try { out.close() } catch (_: Exception) {}; f.delete() }
            }
        }
        override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()
        override fun delete(name: String) { File(dir, name).delete() }
    }
}

/** A folder chosen with the system picker (any storage Android has mounted: internal, SD card, another drive). */
class SafDestination(private val ctx: Context, private val tree: Uri, override val label: String) : Destination {
    private val resolver: ContentResolver get() = ctx.contentResolver
    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun child(parent: Uri, name: String): Uri? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    override fun dir(path: List<String>): DestDir {
        var cur = rootDoc
        for (p in path) cur = child(cur, p) ?: DocumentsContract.createDocument(resolver, cur, DocumentsContract.Document.MIME_TYPE_DIR, p) ?: throw java.io.IOException("Cannot create folder $p")
        return SafDir(cur)
    }

    private inner class SafDir(val parent: Uri) : DestDir {
        override fun sizeOf(name: String): Long? {
            val u = child(parent, name) ?: return null
            resolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) return it.getLong(0) }
            return null
        }
        override fun create(name: String): DestSink {
            val real = freeName(name) { child(parent, it) != null }
            val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", real) ?: throw java.io.IOException("Cannot create $real")
            val pfd = resolver.openFileDescriptor(uri, "w") ?: throw java.io.IOException("Cannot open $real for writing")
            val out = FileOutputStream(pfd.fileDescriptor)
            return object : DestSink {
                override val name = real
                override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
                override fun finish() { out.flush(); try { out.fd.sync() } catch (_: Exception) {}; out.close(); pfd.close() }
                override fun abort() { try { out.close(); pfd.close() } catch (_: Exception) {}; try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) {} }
            }
        }
        override fun open(name: String): InputStream? = child(parent, name)?.let { resolver.openInputStream(it) }
        override fun delete(name: String) { child(parent, name)?.let { try { DocumentsContract.deleteDocument(resolver, it) } catch (_: Exception) {} } }
    }
}
