package com.fatalpuppet.volumex.ui.viewmodel

import android.content.Context
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
                _statusMessage.value = "${filtered.size} item${if (filtered.size != 1) "s" else ""}"
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
