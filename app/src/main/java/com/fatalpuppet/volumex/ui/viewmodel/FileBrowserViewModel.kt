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
                _entries.value = sortEntries(result, _sortBy.value)
                _statusMessage.value = "${result.size} item${if (result.size != 1) "s" else ""}"
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
