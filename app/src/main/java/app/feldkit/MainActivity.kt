package app.feldkit

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
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.feldkit.storage.filesystem.VolumeInfo
import app.feldkit.ui.screens.FileBrowserScreen
import app.feldkit.ui.screens.FilePreviewScreen
import app.feldkit.ui.screens.FileVaultUnlockScreen
import app.feldkit.ui.screens.HomeScreen
import app.feldkit.ui.screens.SettingsScreen
import app.feldkit.ui.screens.TransferScreen
import app.feldkit.ui.screens.VolumePickerScreen
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.FeldKitTheme
import app.feldkit.ui.viewmodel.FileBrowserViewModel
import app.feldkit.ui.viewmodel.MainViewModel
import app.feldkit.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.launch

/**
 * Simple enum-based navigation without Navigation Compose (to avoid additional dependencies).
 */
private fun screenDepth(s: Screen) = when (s) {
    is Screen.Home -> 0
    is Screen.VolumePicker, is Screen.Unlock -> 1
    is Screen.FileBrowser -> 2
    else -> 3
}

/** Forward moves slide in from the right over a slightly receding page; back reverses it. One curve, one duration. */
private fun screenTransition(from: Screen, to: Screen): androidx.compose.animation.ContentTransform {
    val ease = app.feldkit.ui.components.GlassEase
    val forward = screenDepth(to) >= screenDepth(from)
    val dir = if (forward) 1 else -1
    // Fade-through: the old page leaves first, the new one arrives with a short slide, so two busy screens never ghost over each other.
    val enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(420, easing = ease)) { (it * 0.07f).toInt() * dir } +
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, delayMillis = 100, easing = ease))
    val exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260, easing = ease)) { -(it * 0.04f).toInt() * dir } +
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
    return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else 0f }
}

sealed class Screen {
    object Home : Screen()
    object VolumePicker : Screen()
    data class FileBrowser(val volumeIndex: Int) : Screen()
    object Transfers : Screen()
    data class FilePreview(val entry: FileSystemEntry) : Screen()
    object Settings : Screen()
    data class Unlock(val volumeIndex: Int) : Screen()
}

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FeldKit"
        private const val ACTION_USB_PERMISSION = "app.feldkit.USB_PERMISSION"
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
        Log.i(TAG, "FeldKit MainActivity starting")

        transferViewModel.initialize(this)
        app.feldkit.storage.filesystem.FilesystemMounter.enableApfsWrite =
            getSharedPreferences("vx_prefs", MODE_PRIVATE).getBoolean("apfs_write", false)
        app.feldkit.storage.usb.UsbTuning.fastReads = getSharedPreferences("vx_prefs", MODE_PRIVATE).getBoolean("fast_usb", false)
        app.feldkit.storage.filesystem.FilesystemMounter.enableNtfsWrite =
            getSharedPreferences("vx_prefs", MODE_PRIVATE).getBoolean("ntfs_write", false)

        setContent {
            FeldKitTheme {
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

                val haze = dev.chrisbanes.haze.rememberHazeState()
                val overlays = remember { app.feldkit.ui.components.OverlayHost() }
                androidx.compose.runtime.CompositionLocalProvider(
                    app.feldkit.ui.theme.LocalHaze provides haze,
                    app.feldkit.ui.components.LocalOverlayHost provides overlays,
                ) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                // One backdrop for the whole app: screens move over it, it never changes, so glass keeps its surroundings.
                app.feldkit.ui.theme.AmbientBackdrop(haze, androidx.compose.ui.Modifier.fillMaxSize())
                // targetSdk 36+ forces edge-to-edge: keep content clear of status/nav bars and the cutout.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {

                androidx.compose.animation.AnimatedContent(
                    targetState = currentScreen,
                    contentKey = { it::class },
                    transitionSpec = { screenTransition(initialState, targetState) },
                    label = "screen"
                ) { screen ->
                when (screen) {
                    is Screen.Home -> {
                        HomeScreen(
                            viewModel = mainViewModel,
                            onOpenSettings = { settingsReturn = Screen.Home; currentScreen = Screen.Settings },
                            onBrowseVolume = { volumeInfo, idx ->
                                val state = mainViewModel.deviceState.value
                                if (state is app.feldkit.ui.viewmodel.DeviceState.Connected) {
                                    if (state.reader.isLocked(idx)) {
                                        currentScreen = Screen.Unlock(idx)
                                    } else if (state.volumes.size > 1) {
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
                        if (state is app.feldkit.ui.viewmodel.DeviceState.Connected) {
                            VolumePickerScreen(
                                volumes = state.volumes,
                                deviceName = state.deviceName,
                                onSelectVolume = { _, idx ->
                                    if (state.reader.isLocked(idx)) { currentScreen = Screen.Unlock(idx) } else {
                                    fileBrowserViewModel.setReader(state.reader, idx)
                                    currentScreen = Screen.FileBrowser(idx) }
                                },
                                onNavigateBack = { currentScreen = Screen.Home }
                            )
                        }
                    }
                    is Screen.Unlock -> {
                        val state = mainViewModel.deviceState.value
                        if (state is app.feldkit.ui.viewmodel.DeviceState.Connected) {
                            val vol = state.volumes.getOrNull(screen.volumeIndex)
                            if (vol != null) FileVaultUnlockScreen(
                                volume = vol,
                                onUnlock = { secret -> mainViewModel.unlockVolume(screen.volumeIndex, secret) },
                                onSuccess = {
                                    fileBrowserViewModel.setReader(state.reader, screen.volumeIndex)
                                    currentScreen = Screen.FileBrowser(screen.volumeIndex)
                                },
                                onEject = { mainViewModel.eject(); currentScreen = Screen.Home },
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
                overlays.Render()
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
