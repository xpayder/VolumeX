package com.fatalpuppet.volumex

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.screens.FileBrowserScreen
import com.fatalpuppet.volumex.ui.screens.FilePreviewScreen
import com.fatalpuppet.volumex.ui.screens.FileVaultUnlockScreen
import com.fatalpuppet.volumex.ui.screens.HomeScreen
import com.fatalpuppet.volumex.ui.screens.SettingsScreen
import com.fatalpuppet.volumex.ui.screens.TransferScreen
import com.fatalpuppet.volumex.ui.screens.VolumePickerScreen
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.ui.theme.VolumeXTheme
import com.fatalpuppet.volumex.ui.viewmodel.FileBrowserViewModel
import com.fatalpuppet.volumex.ui.viewmodel.MainViewModel
import com.fatalpuppet.volumex.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.launch

/**
 * Simple enum-based navigation without Navigation Compose (to avoid additional dependencies).
 */
sealed class Screen {
    object Home : Screen()
    object VolumePicker : Screen()
    data class FileBrowser(val volumeIndex: Int) : Screen()
    object Transfers : Screen()
    data class FilePreview(val entry: FileSystemEntry) : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VolumeX"
        private const val ACTION_USB_PERMISSION = "com.fatalpuppet.volumex.USB_PERMISSION"
    }

    private val mainViewModel: MainViewModel by viewModels()
    private val fileBrowserViewModel: FileBrowserViewModel by viewModels()
    private val transferViewModel: TransferViewModel by viewModels()

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let {
                        Log.i(TAG, "USB permission granted for: ${device.productName}")
                        mainViewModel.connectDevice(this@MainActivity, device)
                    }
                } else {
                    Log.e(TAG, "USB permission denied")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VolumeX MainActivity starting")

        transferViewModel.initialize(this)
        com.fatalpuppet.volumex.storage.filesystem.FilesystemMounter.enableApfsWrite =
            getSharedPreferences("vx_prefs", MODE_PRIVATE).getBoolean("apfs_write", false)

        setContent {
            VolumeXTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                var settingsReturn by remember { mutableStateOf<Screen>(Screen.Home) }

                androidx.activity.compose.BackHandler(enabled = currentScreen !is Screen.Home) {
                    when (currentScreen) {
                        is Screen.FilePreview -> currentScreen = Screen.FileBrowser(0)
                        is Screen.FileBrowser -> when {
                            fileBrowserViewModel.selectedEntries.value.isNotEmpty() -> fileBrowserViewModel.clearSelection()
                            fileBrowserViewModel.breadcrumbs.value.size > 1 -> fileBrowserViewModel.navigateUp()
                            else -> currentScreen = Screen.Home
                        }
                        is Screen.Settings -> currentScreen = settingsReturn
                        else -> currentScreen = Screen.Home
                    }
                }

                // targetSdk 36+ forces edge-to-edge: keep content clear of status/nav bars and the cutout.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(com.fatalpuppet.volumex.ui.theme.DeepNavy)
                        .safeDrawingPadding()
                ) {

                when (val screen = currentScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            viewModel = mainViewModel,
                            onOpenSettings = { settingsReturn = Screen.Home; currentScreen = Screen.Settings },
                            onBrowseVolume = { volumeInfo, idx ->
                                val state = mainViewModel.deviceState.value
                                if (state is com.fatalpuppet.volumex.ui.viewmodel.DeviceState.Connected) {
                                    // If multiple volumes, show picker
                                    if (state.volumes.size > 1) {
                                        currentScreen = Screen.VolumePicker
                                    } else {
                                        fileBrowserViewModel.setReader(state.reader, idx)
                                        currentScreen = Screen.FileBrowser(idx)
                                    }
                                }
                            }
                        )
                    }
                    is Screen.VolumePicker -> {
                        val state = mainViewModel.deviceState.value
                        if (state is com.fatalpuppet.volumex.ui.viewmodel.DeviceState.Connected) {
                            VolumePickerScreen(
                                volumes = state.volumes,
                                deviceName = state.deviceName,
                                onSelectVolume = { _, idx ->
                                    fileBrowserViewModel.setReader(state.reader, idx)
                                    currentScreen = Screen.FileBrowser(idx)
                                },
                                onNavigateBack = { currentScreen = Screen.Home }
                            )
                        }
                    }
                    is Screen.FileBrowser -> {
                        FileBrowserScreen(
                            viewModel = fileBrowserViewModel,
                            onNavigateBack = { currentScreen = Screen.Home },
                            onOpenPreview = { entry -> currentScreen = Screen.FilePreview(entry) },
                            onOpenSettings = { settingsReturn = currentScreen; currentScreen = Screen.Settings }
                        )
                    }
                    is Screen.FilePreview -> {
                        val previewEntry = (screen as Screen.FilePreview).entry
                        val browserEntries by fileBrowserViewModel.entries.collectAsState()
                        val browserWritable by fileBrowserViewModel.writable.collectAsState()
                        FilePreviewScreen(
                            entry = previewEntry,
                            siblings = browserEntries.filter { it.fileType == previewEntry.fileType && !it.isDirectory }.ifEmpty { listOf(previewEntry) },
                            canDelete = browserWritable,
                            onDelete = { fileBrowserViewModel.deleteEntries(listOf(it)) },
                            onNavigateBack = { currentScreen = Screen.FileBrowser(0) }
                        )
                    }
                    is Screen.Settings -> {
                        val showHidden by fileBrowserViewModel.showHidden.collectAsState()
                        SettingsScreen(
                            showHiddenFiles = showHidden,
                            onToggleHiddenFiles = { fileBrowserViewModel.toggleHidden() },
                            onNavigateBack = { currentScreen = settingsReturn }
                        )
                    }
                    is Screen.Transfers -> {
                        TransferScreen(
                            viewModel = transferViewModel,
                            onNavigateBack = { currentScreen = Screen.Home }
                        )
                    }
                }
                }
            }
        }

        // Register USB permission receiver
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            androidx.core.content.ContextCompat.registerReceiver(
                this, usbPermissionReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver", e)
        }

        handleDebugImage(intent)

        // Auto-scan for USB devices
        lifecycleScope.launch {
            scanForUsbDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) { /* ignore */ }
        mainViewModel.disconnectDevice()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugImage(intent)
    }

    /** Debug builds only: `am start ... --es vx_image <path>` mounts a disk image instead of a USB drive. */
    private fun handleDebugImage(intent: android.content.Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        intent?.getStringExtra("vx_image")?.let { mainViewModel.mountImage(it) }
        // Same code path as the "Add files" picker, driven by file path (for automated tests).
        intent?.getStringExtra("vx_import")?.let { path ->
            fileBrowserViewModel.importUris(this, listOf(android.net.Uri.fromFile(java.io.File(path))))
        }
        // Navigate the browser (debug automation): --es vx_cd /folder
        intent?.getStringExtra("vx_cd")?.let { fileBrowserViewModel.navigateTo(it) }
    }

    private fun scanForUsbDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            Log.i(TAG, "No USB devices found")
            return
        }

        for (device in devices.values) {
            if (!usbManager.hasPermission(device)) {
                Log.i(TAG, "Requesting USB permission for ${device.productName}")
                val intent = Intent(ACTION_USB_PERMISSION)
                val pendingIntent = PendingIntent.getBroadcast(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, pendingIntent)
            } else {
                Log.i(TAG, "Have permission for ${device.productName}, connecting...")
                mainViewModel.connectDevice(this, device)
            }
        }
    }
}
