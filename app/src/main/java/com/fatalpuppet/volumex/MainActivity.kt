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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.screens.FileBrowserScreen
import com.fatalpuppet.volumex.ui.screens.HomeScreen
import com.fatalpuppet.volumex.ui.screens.TransferScreen
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
    data class FileBrowser(val volumeIndex: Int) : Screen()
    object Transfers : Screen()
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

        setContent {
            VolumeXTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                when (val screen = currentScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            viewModel = mainViewModel,
                            onBrowseVolume = { volumeInfo, idx ->
                                val state = mainViewModel.deviceState.value
                                if (state is com.fatalpuppet.volumex.ui.viewmodel.DeviceState.Connected) {
                                    fileBrowserViewModel.setReader(state.reader, idx)
                                }
                                currentScreen = Screen.FileBrowser(idx)
                            }
                        )
                    }
                    is Screen.FileBrowser -> {
                        FileBrowserScreen(
                            viewModel = fileBrowserViewModel,
                            onNavigateBack = { currentScreen = Screen.Home }
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

        // Register USB permission receiver
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            registerReceiver(usbPermissionReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver", e)
        }

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
