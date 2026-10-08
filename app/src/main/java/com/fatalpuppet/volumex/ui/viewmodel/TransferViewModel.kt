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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TransferViewModel : ViewModel() {
    companion object {
        private const val TAG = "VolumeX"
    }

    private val _queue = MutableStateFlow<List<TransferProgress>>(emptyList())
    val queue: StateFlow<List<TransferProgress>> = _queue.asStateFlow()

    private val _isTransferring = MutableStateFlow(false)
    val isTransferring: StateFlow<Boolean> = _isTransferring.asStateFlow()

    private var manager: FileOperationManager? = null

    fun initialize(context: Context) {
        manager = FileOperationManager(context)
    }

    fun transferToAndroid(
        context: Context,
        reader: FileSystemReader,
        entries: List<FileSystemEntry>
    ) {
        if (_isTransferring.value) return
        val mgr = manager ?: FileOperationManager(context).also { manager = it }

        viewModelScope.launch {
            _isTransferring.value = true
            val progresses = entries.map { TransferProgress(it.name, 0, it.size) }.toMutableList()
            _queue.value = progresses.toList()

            entries.forEachIndexed { idx, entry ->
                val result = mgr.copyToAndroid(reader, entry) { prog ->
                    progresses[idx] = prog
                    _queue.value = progresses.toList()
                }
                when (result) {
                    is TransferResult.Success -> {
                        progresses[idx] = TransferProgress(entry.name, result.bytesTransferred, entry.size, isComplete = true)
                    }
                    is TransferResult.Failure -> {
                        progresses[idx] = TransferProgress(entry.name, 0, entry.size, isComplete = true, error = result.reason)
                    }
                    TransferResult.Cancelled -> {
                        progresses[idx] = TransferProgress(entry.name, 0, entry.size, isComplete = true, error = "Cancelled")
                    }
                }
                _queue.value = progresses.toList()
            }

            _isTransferring.value = false
        }
    }

    fun cancelAll() {
        manager?.cancel()
    }

    fun clearQueue() {
        _queue.value = emptyList()
    }
}
