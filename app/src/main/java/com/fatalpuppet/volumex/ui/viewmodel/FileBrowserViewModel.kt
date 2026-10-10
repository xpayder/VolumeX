package com.fatalpuppet.volumex.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.fatalpuppet.volumex.storage.ActiveDriveSession
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fatalpuppet.volumex.storage.FileOperationManager
import com.fatalpuppet.volumex.storage.TransferProgress
import com.fatalpuppet.volumex.storage.TransferResult
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BreadcrumbItem(val name: String, val path: String)

enum class ViewMode { LIST, GRID }

data class Clipboard(val entries: List<FileSystemEntry>, val move: Boolean)

class FileBrowserViewModel : ViewModel() {
    companion object {
        private const val TAG = "VolumeX"
    }

    private val _entries = MutableStateFlow<List<FileSystemEntry>>(emptyList())
    val entries: StateFlow<List<FileSystemEntry>> = _entries.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _breadcrumbs = MutableStateFlow(listOf(BreadcrumbItem("Root", "/")))
    val breadcrumbs: StateFlow<List<BreadcrumbItem>> = _breadcrumbs.asStateFlow()

    private val _selectedEntries = MutableStateFlow<Set<String>>(emptySet())
    val selectedEntries: StateFlow<Set<String>> = _selectedEntries.asStateFlow()

    private val _transferProgress = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transferProgress: StateFlow<List<TransferProgress>> = _transferProgress.asStateFlow()

    private val _writable = MutableStateFlow(false)
    /** True when the mounted filesystem supports writing (import, new folder, rename, delete). */
    val writable: StateFlow<Boolean> = _writable.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot user-facing message (errors / confirmations) shown as a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private var appCtx: Context? = null
    fun attach(ctx: Context) { appCtx = ctx.applicationContext }

    /** Runs a long copy on IO under the foreground transfer service so it survives the screen turning off. */
    private fun launchTransfer(ctx: Context?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val c = ctx ?: appCtx
        c?.let { com.fatalpuppet.volumex.services.TransferService.begin(it) }
        try { block() } finally { c?.let { com.fatalpuppet.volumex.services.TransferService.end(it) } }
    }
    private fun say(text: String) { Log.w(TAG, "UI message: $text"); _message.value = text }

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()
    fun setClipboard(entries: List<FileSystemEntry>, move: Boolean) { _clipboard.value = Clipboard(entries, move); say(if (move) "Cut ${entries.size} item(s) - open a folder and tap Paste" else "Copied ${entries.size} item(s) - open a folder and tap Paste") }
    fun clearClipboard() { _clipboard.value = null }
    private var viewModeChosenByUser = false

    private var reader: FileSystemReader? = null
    private var currentVolumeIndex: Int = 0
    private var currentPath: String = "/"

    // Sort state
    private val _sortBy = MutableStateFlow(SortBy.NAME)
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    // View mode (list / grid)
    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    // Hidden files toggle
    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<FileSystemEntry>>(emptyList())
    val searchResults: StateFlow<List<FileSystemEntry>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun setReader(fsReader: FileSystemReader, volumeIndex: Int = 0) {
        reader = fsReader
        currentVolumeIndex = volumeIndex
        if (fsReader is com.fatalpuppet.volumex.storage.filesystem.CompositeReader) {
            ActiveDriveSession.writer = fsReader.writerFor(volumeIndex)
        }
        ActiveDriveSession.currentVolumeIndex = volumeIndex
        _writable.value = ActiveDriveSession.writer != null
        navigateTo("/")
    }

    fun navigateTo(path: String) {
        currentPath = path
        updateBreadcrumbs(path)
        loadDirectory(path)
    }

    fun navigateUp() {
        if (currentPath == "/" || currentPath.isEmpty()) return
        val parentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" }
        navigateTo(parentPath)
    }

    fun openEntry(entry: FileSystemEntry) {
        if (entry.isDirectory) {
            navigateTo(entry.path)
        }
    }

    fun toggleSelection(entry: FileSystemEntry) {
        val current = _selectedEntries.value.toMutableSet()
        if (current.contains(entry.path)) current.remove(entry.path) else current.add(entry.path)
        _selectedEntries.value = current
    }

    fun clearSelection() {
        _selectedEntries.value = emptySet()
    }

    fun setSortBy(sort: SortBy) {
        _sortBy.value = sort
        _entries.value = sortEntries(_entries.value, sort)
    }

    fun toggleViewMode() {
        viewModeChosenByUser = true
        _viewMode.value = if (_viewMode.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
    }

    fun toggleHidden() {
        _showHidden.value = !_showHidden.value
        loadDirectory(currentPath)
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val results = try {
                reader?.searchFiles(query, currentVolumeIndex) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                emptyList()
            }
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun getFolderDetails(entry: FileSystemEntry) {
        if (!entry.isDirectory) return
        viewModelScope.launch(Dispatchers.IO) {
            val (count, total) = computeFolderDetails(entry.path, depth = 0, maxDepth = 5)
            val updatedEntries = _entries.value.map {
                if (it.path == entry.path) it.copy(childCount = count, totalSize = total) else it
            }
            _entries.value = updatedEntries
        }
    }

    private fun computeFolderDetails(path: String, depth: Int, maxDepth: Int): Pair<Int, Long> {
        if (depth > maxDepth) return Pair(0, 0L)
        val fsReader = reader ?: return Pair(0, 0L)
        val children = try { fsReader.listDirectory(currentVolumeIndex, path) } catch (e: Exception) { return Pair(0, 0L) }
        var count = children.size
        var total = children.filter { !it.isDirectory }.sumOf { it.size }
        for (child in children.filter { it.isDirectory }) {
            val (c, s) = computeFolderDetails(child.path, depth + 1, maxDepth)
            count += c
            total += s
        }
        return Pair(count, total)
    }

    fun copySelectedToAndroid(context: Context) {
        val fsReader = reader ?: return
        val selectedPaths = _selectedEntries.value
        val toTransfer = _entries.value.filter { selectedPaths.contains(it.path) && !it.isDirectory }
        if (toTransfer.isEmpty()) return

        viewModelScope.launch {
            val manager = FileOperationManager(context)
            val progresses = toTransfer.map { entry ->
                TransferProgress(entry.name, 0, entry.size)
            }.toMutableList()
            _transferProgress.value = progresses.toList()

            toTransfer.forEachIndexed { idx, entry ->
                val result = manager.copyToAndroid(fsReader, entry) { prog ->
                    progresses[idx] = prog
                    _transferProgress.value = progresses.toList()
                }
                when (result) {
                    is TransferResult.Success -> {
                        progresses[idx] = TransferProgress(entry.name, result.bytesTransferred, entry.size, isComplete = true)
                        _transferProgress.value = progresses.toList()
                    }
                    is TransferResult.Failure -> {
                        progresses[idx] = TransferProgress(entry.name, 0, entry.size, isComplete = true, error = result.reason)
                        _transferProgress.value = progresses.toList()
                    }
                    else -> {}
                }
            }
            clearSelection()
        }
    }


    // ── Write operations ──────────────────────────────────────────────────────

    /** Directory entry for [path] (usable as a write parent), or null if it can't be resolved. */
    private fun resolveDirEntry(path: String): FileSystemEntry? {
        val r = reader ?: return null
        var cur = r.rootEntry(currentVolumeIndex)
        var p = "/"
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = r.listDirectory(currentVolumeIndex, p).firstOrNull { it.name == part && it.isDirectory } ?: return null
            p = cur.path
        }
        return cur
    }

    private fun updateProgress(list: MutableList<TransferProgress>, idx: Int, p: TransferProgress) {
        list[idx] = p
        _transferProgress.value = list.toList()
    }

    /** Copy files picked on the phone onto the current folder of the drive. */
    fun importUris(context: Context, uris: List<Uri>) {
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        val dest = currentPath
        Log.i(TAG, "importUris: ${uris.size} file(s) into '$dest'")
        launchTransfer(context) {
            val parent = resolveDirEntry(dest) ?: run { say("Could not open the destination folder"); return@launchTransfer }
            val resolver = context.contentResolver
            val progresses = mutableListOf<TransferProgress>()
            val infos = uris.map { u ->
                var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                var size = -1L
                resolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    }
                }
                Triple(u, name, size)
            }
            infos.forEach { progresses.add(TransferProgress(it.second, 0, it.third.coerceAtLeast(0))) }
            _transferProgress.value = progresses.toList()
            infos.forEachIndexed { idx, (uri, name, size0) ->
                try {
                    val size = if (size0 >= 0) size0 else resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size < 0) throw java.io.IOException("unknown file size")
                    Log.i(TAG, "importUris: writing '$name' ($size bytes)")
                    val ok = resolver.openInputStream(uri)?.use { input ->
                        w.writeFileStream(parent, name, size, input) { done ->
                            updateProgress(progresses, idx, TransferProgress(name, done, size))
                        }
                    } ?: false
                    updateProgress(progresses, idx, if (ok) TransferProgress(name, size, size, isComplete = true)
                        else TransferProgress(name, 0, size, isComplete = true, error = "Write failed (name exists, drive full or unsupported name)"))
                } catch (e: Exception) {
                    Log.e(TAG, "import failed: $name", e)
                    updateProgress(progresses, idx, TransferProgress(name, 0, size0.coerceAtLeast(0), isComplete = true, error = e.message ?: "Error"))
                }
            }
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }

    fun createFolder(name: String) {
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        val dest = currentPath
        viewModelScope.launch(Dispatchers.IO) {
            val parent = resolveDirEntry(dest)
            val ok = parent != null && try { w.createDirectory(parent, name.trim()) } catch (e: Exception) { false }
            say(if (ok) "Folder created" else "Could not create folder (name exists or invalid)")
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }

    fun deleteEntries(entries: List<FileSystemEntry>) {
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        viewModelScope.launch(Dispatchers.IO) {
            var failed = 0
            for (e in entries) if (!(try { w.deleteEntry(e) } catch (x: Exception) { false })) failed++
            say(if (failed == 0) "Deleted ${entries.size} item${if (entries.size != 1) "s" else ""}" else "$failed item(s) could not be deleted")
            _selectedEntries.value = emptySet()
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }

    fun renameEntry(entry: FileSystemEntry, newName: String) {
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { w.renameEntry(entry, newName.trim()) } catch (e: Exception) { false }
            say(if (ok) "Renamed" else "Could not rename (name exists or invalid)")
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }


    // ── Copy / move inside the drive, folder upload ──────────────────────────
    private fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.'); val stem = if (dot > 0) name.substring(0, dot) else name; val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1; var cand: String
        do { cand = if (n == 1) "$stem copy$ext" else "$stem copy $n$ext"; n++ } while (cand in existing)
        return cand
    }

    fun pasteHere() {
        val clip = _clipboard.value ?: return
        val r = reader ?: return
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        val dest = currentPath
        launchTransfer(null) {
            val progresses = mutableListOf<TransferProgress>()
            _transferProgress.value = emptyList()
            var failed = 0
            fun copyEntry(e: FileSystemEntry, dstParent: FileSystemEntry, dstPath: String, rename: String?): Boolean {
                val existing = r.listDirectory(currentVolumeIndex, dstPath).map { it.name }.toSet()
                val name = uniqueName(existing, rename ?: e.name)
                if (e.isDirectory) {
                    // never copy a folder into itself
                    if (dstPath == e.path || dstPath.startsWith(e.path.trimEnd('/') + "/")) { say("Cannot copy a folder into itself"); return false }
                    if (!w.createDirectory(dstParent, name)) return false
                    val newDir = r.listDirectory(currentVolumeIndex, dstPath).firstOrNull { it.name == name && it.isDirectory } ?: return false
                    var ok = true
                    for (c in r.listDirectory(currentVolumeIndex, e.path)) ok = copyEntry(c, newDir, newDir.path, null) && ok
                    return ok
                }
                val idx = progresses.size
                progresses.add(TransferProgress(name, 0, e.size)); _transferProgress.value = progresses.toList()
                val pin = java.io.PipedInputStream(1 shl 20); val pout = java.io.PipedOutputStream(pin)
                val producer = Thread { try { r.readFileTo(e, pout) } catch (x: Exception) { Log.e(TAG, "paste read failed", x) } finally { try { pout.close() } catch (_: Exception) {} } }
                producer.start()
                val ok = try { w.writeFileStream(dstParent, name, e.size, pin) { n -> updateProgress(progresses, idx, TransferProgress(name, n, e.size)) } } catch (x: Exception) { false }
                try { pin.close() } catch (_: Exception) {}
                producer.join()
                updateProgress(progresses, idx, if (ok) TransferProgress(name, e.size, e.size, isComplete = true) else TransferProgress(name, 0, e.size, isComplete = true, error = "Could not copy (drive full or name rejected)"))
                return ok
            }
            val dstParent = resolveDirEntry(dest)
            if (dstParent == null) { say("Could not open the destination folder"); return@launchTransfer }
            for (e in clip.entries) {
                val ok = try { copyEntry(e, dstParent, dest, null) } catch (x: Exception) { Log.e(TAG, "paste failed", x); false }
                if (!ok) failed++
                else if (clip.move) { try { w.deleteEntry(e) } catch (x: Exception) { failed++ } }
            }
            _clipboard.value = null
            say(if (failed == 0) (if (clip.move) "Moved ${clip.entries.size} item(s)" else "Copied ${clip.entries.size} item(s)") else "$failed item(s) failed")
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }

    /** Upload a folder picked on the phone (recursive) into the current folder. */
    fun importTree(context: Context, treeUri: Uri) {
        val w = ActiveDriveSession.writer ?: run { say("This drive is mounted read-only"); return }
        val r = reader ?: return
        val dest = currentPath
        launchTransfer(context) {
            val resolver = context.contentResolver
            val progresses = mutableListOf<TransferProgress>(); _transferProgress.value = emptyList()
            var failed = 0
            fun children(docUri: Uri): List<Triple<Uri, String, Pair<String, Long>>> {
                val id = DocumentsContract.getDocumentId(docUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
                val out = ArrayList<Triple<Uri, String, Pair<String, Long>>>()
                resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    while (c.moveToNext()) out.add(Triple(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), c.getString(1) ?: "item", (c.getString(2) ?: "") to (if (c.isNull(3)) -1L else c.getLong(3))))
                }
                return out
            }
            fun upload(doc: Uri, name: String, mime: String, size: Long, dstParent: FileSystemEntry, dstPath: String) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!w.createDirectory(dstParent, name)) { failed++; return }
                    val nd = r.listDirectory(currentVolumeIndex, dstPath).firstOrNull { it.name == name && it.isDirectory } ?: run { failed++; return }
                    for ((u, n, ms) in children(doc)) upload(u, n, ms.first, ms.second, nd, nd.path)
                } else {
                    val idx = progresses.size
                    progresses.add(TransferProgress(name, 0, size.coerceAtLeast(0))); _transferProgress.value = progresses.toList()
                    val ok = try {
                        val sz = if (size >= 0) size else resolver.openAssetFileDescriptor(doc, "r")?.use { it.length } ?: -1L
                        resolver.openInputStream(doc)?.use { input -> w.writeFileStream(dstParent, name, sz, input) { n -> updateProgress(progresses, idx, TransferProgress(name, n, sz)) } } ?: false
                    } catch (x: Exception) { Log.e(TAG, "upload failed: $name", x); false }
                    updateProgress(progresses, idx, if (ok) TransferProgress(name, size, size, isComplete = true) else TransferProgress(name, 0, size.coerceAtLeast(0), isComplete = true, error = "Could not write"))
                    if (!ok) failed++
                }
            }
            val parent = resolveDirEntry(dest) ?: run { say("Could not open the destination folder"); return@launchTransfer }
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            var rootName = "Folder"
            resolver.query(rootDoc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) rootName = it.getString(0) ?: rootName }
            upload(rootDoc, rootName, DocumentsContract.Document.MIME_TYPE_DIR, 0, parent, dest)
            say(if (failed == 0) "Folder uploaded" else "$failed item(s) failed")
            withContext(Dispatchers.Main) { loadDirectory(currentPath) }
        }
    }

    // ── Copy from the drive to a folder the user picks (Storage Access Framework) ──

    /** Copy files/folders to Downloads/VolumeX (no picker needed). */
    fun copyToDownloads(context: Context, items: List<FileSystemEntry>) {
        val r = reader ?: return
        launchTransfer(context) {
            val base = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "VolumeX")
            val progresses = mutableListOf<TransferProgress>()
            _transferProgress.value = emptyList()
            fun uniq(dir: java.io.File, name: String): java.io.File {
                var f = java.io.File(dir, name); var n = 1
                val dot = name.lastIndexOf('.'); val stem = if (dot > 0) name.substring(0, dot) else name; val ext = if (dot > 0) name.substring(dot) else ""
                while (f.exists()) { f = java.io.File(dir, "$stem ($n)$ext"); n++ }
                return f
            }
            fun copyOne(e: FileSystemEntry, dir: java.io.File) {
                if (e.isDirectory) {
                    val d = java.io.File(dir, e.name).also { it.mkdirs() }
                    r.listDirectory(currentVolumeIndex, e.path).forEach { copyOne(it, d) }
                    return
                }
                val idx = progresses.size
                progresses.add(TransferProgress(e.name, 0, e.size)); _transferProgress.value = progresses.toList()
                dir.mkdirs()
                val dest = uniq(dir, e.name)
                try {
                    val ok = java.io.BufferedOutputStream(java.io.FileOutputStream(dest), 1 shl 20).use { out ->
                        r.readFileTo(e, out) { n -> updateProgress(progresses, idx, TransferProgress(e.name, n, e.size)) }
                    }
                    if (!ok) dest.delete()
                    updateProgress(progresses, idx, if (ok) TransferProgress(e.name, e.size, e.size, isComplete = true)
                        else TransferProgress(e.name, 0, e.size, isComplete = true, error = "Could not read file from the drive"))
                } catch (x: Exception) {
                    dest.delete(); Log.e(TAG, "copyToDownloads failed: ${e.name}", x)
                    updateProgress(progresses, idx, TransferProgress(e.name, 0, e.size, isComplete = true, error = x.message ?: "Error"))
                }
            }
            items.forEach { copyOne(it, base) }
            say("Saved to Downloads/VolumeX")
            _selectedEntries.value = emptySet()
        }
    }

    fun entriesByPaths(paths: Set<String>): List<FileSystemEntry> = _entries.value.filter { it.path in paths }

    fun copyToTree(context: Context, treeUri: Uri, items: List<FileSystemEntry>) {
        val r = reader ?: return
        val resolver = context.contentResolver
        launchTransfer(context) {
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val progresses = mutableListOf<TransferProgress>()
            _transferProgress.value = emptyList()

            fun copyOne(entry: FileSystemEntry, parentDoc: Uri) {
                if (entry.isDirectory) {
                    val dirDoc = DocumentsContract.createDocument(resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, entry.name) ?: return
                    r.listDirectory(currentVolumeIndex, entry.path).forEach { copyOne(it, dirDoc) }
                    return
                }
                val idx = progresses.size
                progresses.add(TransferProgress(entry.name, 0, entry.size))
                _transferProgress.value = progresses.toList()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension) ?: "application/octet-stream"
                try {
                    val doc = DocumentsContract.createDocument(resolver, parentDoc, mime, entry.name)
                        ?: throw java.io.IOException("cannot create file in the chosen folder")
                    val ok = resolver.openOutputStream(doc)?.use { out ->
                        val buffered = java.io.BufferedOutputStream(out, 1 shl 20)
                        val done = r.readFileTo(entry, buffered) { n -> updateProgress(progresses, idx, TransferProgress(entry.name, n, entry.size)) }
                        buffered.flush(); done
                    } ?: false
                    updateProgress(progresses, idx, if (ok) TransferProgress(entry.name, entry.size, entry.size, isComplete = true)
                        else TransferProgress(entry.name, 0, entry.size, isComplete = true, error = "Could not read file from the drive"))
                } catch (e: Exception) {
                    Log.e(TAG, "copyToTree failed: ${entry.name}", e)
                    updateProgress(progresses, idx, TransferProgress(entry.name, 0, entry.size, isComplete = true, error = e.message ?: "Error"))
                }
            }
            items.forEach { copyOne(it, rootDoc) }
            _selectedEntries.value = emptySet()
        }
    }

    private fun loadDirectory(path: String) {
        val fsReader = reader ?: run {
            _statusMessage.value = "No filesystem mounted"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Loading..."

            val result = withContext(Dispatchers.IO) {
                try {
                    fsReader.listDirectory(currentVolumeIndex, path)
                } catch (e: Exception) {
                    Log.e(TAG, "Error listing directory", e)
                    null
                }
            }

            if (result != null) {
                val filtered = if (_showHidden.value) result else result.filter { !it.name.startsWith(".") }
                _entries.value = sortEntries(filtered, _sortBy.value)
                if (!viewModeChosenByUser) {
                    val files = filtered.filter { !it.isDirectory }
                    val media = files.count { it.fileType == com.fatalpuppet.volumex.storage.filesystem.FileType.IMAGE || it.fileType == com.fatalpuppet.volumex.storage.filesystem.FileType.VIDEO }
                    _viewMode.value = if (files.isNotEmpty() && media * 2 >= files.size) ViewMode.GRID else ViewMode.LIST
                }
                _statusMessage.value = "${filtered.size} item${if (filtered.size != 1) "s" else ""}" +
                    if (_writable.value) "" else " · read-only"
            } else {
                _entries.value = emptyList()
                _statusMessage.value = "Error loading directory"
            }
            _isLoading.value = false
        }
    }

    private fun updateBreadcrumbs(path: String) {
        val crumbs = mutableListOf(BreadcrumbItem("Root", "/"))
        if (path != "/") {
            val parts = path.trim('/').split("/")
            var accumulated = ""
            for (part in parts) {
                accumulated = "$accumulated/$part"
                crumbs.add(BreadcrumbItem(part, accumulated))
            }
        }
        _breadcrumbs.value = crumbs
    }

    private fun sortEntries(entries: List<FileSystemEntry>, sort: SortBy): List<FileSystemEntry> {
        val (dirs, files) = entries.partition { it.isDirectory }
        val sortedDirs = when (sort) {
            SortBy.NAME -> dirs.sortedBy { it.name.lowercase() }
            SortBy.SIZE -> dirs.sortedBy { it.name.lowercase() }
            SortBy.DATE -> dirs.sortedByDescending { it.modifiedAt }
            SortBy.TYPE -> dirs.sortedBy { it.name.lowercase() }
        }
        val sortedFiles = when (sort) {
            SortBy.NAME -> files.sortedBy { it.name.lowercase() }
            SortBy.SIZE -> files.sortedByDescending { it.size }
            SortBy.DATE -> files.sortedByDescending { it.modifiedAt }
            SortBy.TYPE -> files.sortedWith(compareBy({ it.extension }, { it.name.lowercase() }))
        }
        return sortedDirs + sortedFiles
    }

    fun clearTransferProgress() {
        _transferProgress.value = emptyList()
    }

    // Legacy setters for backward compat with existing MainActivity
    fun setFiles(fileList: List<com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsFileEntry>) {
        _entries.value = fileList.map { it.toFileSystemEntry() }
        _isLoading.value = false
        _statusMessage.value = "Loaded ${fileList.size} files"
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setStatus(statusText: String) {
        _statusMessage.value = statusText
    }
}

private fun com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsFileEntry.toFileSystemEntry(): FileSystemEntry =
    FileSystemEntry(
        name = name,
        path = "/$name",
        isDirectory = isDirectory,
        size = fileSize,
        createdAt = creationTime,
        modifiedAt = modificationTime,
        inodeOid = objectId
    )

enum class SortBy { NAME, SIZE, DATE, TYPE }
