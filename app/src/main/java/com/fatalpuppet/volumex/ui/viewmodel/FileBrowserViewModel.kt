// file: app/src/main/java/com/fatalpuppet/volumex/ui/viewmodels/FileBrowserViewModel.kt
package com.fatalpuppet.volumex.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsFileEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileBrowserViewModel : ViewModel() {

    private val _files = MutableStateFlow<List<ApfsFileEntry>>(emptyList())
    val files: StateFlow<List<ApfsFileEntry>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()

    fun setFiles(fileList: List<ApfsFileEntry>) {
        viewModelScope.launch {
            _files.value = fileList
            _isLoading.value = false
            _status.value = "Loaded ${fileList.size} files"
        }
    }

    fun setLoading(loading: Boolean) {
        viewModelScope.launch {
            _isLoading.value = loading
        }
    }

    fun setStatus(statusText: String) {
        viewModelScope.launch {
            _status.value = statusText
        }
    }
}