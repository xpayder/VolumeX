// file: app/src/main/java/com/fatalpuppet/volumex/MainActivity.kt
package com.fatalpuppet.volumex

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import android.app.PendingIntent
import android.content.pm.PackageManager
import androidx.compose.ui.unit.dp

import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsFileEntry
import com.fatalpuppet.volumex.storage.usb.UsbBlockDeviceReader
import com.fatalpuppet.volumex.ui.theme.VolumeXTheme
import com.fatalpuppet.volumex.ui.viewmodels.FileBrowserViewModel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: FileBrowserViewModel by viewModels()

    companion object {
        private const val TAG = "VolumeX"
        private const val ACTION_USB_PERMISSION = "com.fatalpuppet.volumex.USB_PERMISSION"
    }

    // USB permission receiver
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.i(TAG, "USB permission granted for device: ${device.productName}")
                            // Scan this device
                            lifecycleScope.launch {
                                scanDevice(device)
                            }
                        }
                    } else {
                        Log.e(TAG, "USB permission denied")
                        viewModel.setStatus("USB permission denied")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")

        setContent {
            VolumeXTheme {
                FileBrowserScreen(viewModel)
            }
        }

        try {
            // Register USB permission receiver
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            registerReceiver(usbPermissionReceiver, filter)
            Log.i(TAG, "USB receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB receiver", e)
        }

        // Auto-scan for USB drives when app starts
        lifecycleScope.launch {
            try {
                viewModel.setStatus("Scanning for USB devices...")
                scanForUsbDrives()
            } catch (e: Exception) {
                Log.e(TAG, "Error in scan", e)
                viewModel.setStatus("Error: ${e.message}")
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private suspend fun scanForUsbDrives() {
        withContext(Dispatchers.IO) {
            try {
                val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager

                if (usbManager == null) {
                    Log.e(TAG, "USB Manager is null")
                    withContext(Dispatchers.Main) {
                        viewModel.setStatus("USB service not available")
                    }
                    return@withContext
                }

                val devices = usbManager.deviceList

                if (devices.isEmpty()) {
                    Log.i(TAG, "No USB devices detected")
                    withContext(Dispatchers.Main) {
                        viewModel.setStatus("No USB devices detected. Please connect an APFS drive.")
                    }
                    return@withContext
                }

                Log.i(TAG, "Found ${devices.size} USB device(s)")

                for (device in devices.values) {
                    try {
                        Log.i(TAG, "USB Device: ${device.productName ?: "Unknown"} (VID:${device.vendorId} PID:${device.productId})")

                        // Check if we have permission
                        if (!usbManager.hasPermission(device)) {
                            Log.i(TAG, "Requesting USB permission for device...")
                            // Request permission
                            val intent = Intent(ACTION_USB_PERMISSION)
                            val pendingIntent = PendingIntent.getBroadcast(
                                this@MainActivity,
                                0,
                                intent,
                                PendingIntent.FLAG_IMMUTABLE
                            )
                            usbManager.requestPermission(device, pendingIntent)
                            continue
                        }

                        // We have permission, scan the device
                        scanDevice(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing device", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning USB devices", e)
                withContext(Dispatchers.Main) {
                    viewModel.setStatus("Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun scanDevice(device: UsbDevice) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Scanning device: ${device.productName}")
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val reader = UsbBlockDeviceReader(usbManager, device)

                withContext(Dispatchers.Main) {
                    viewModel.setStatus("Opening USB device...")
                }

                if (reader.open()) {
                    Log.i(TAG, "USB device opened successfully!")
                    withContext(Dispatchers.Main) {
                        viewModel.setStatus("USB device connected - scanning for APFS...")
                    }

                    // The APFS parsing happens inside readDiskLayout()
                    // which is called from open()

                    // Close the reader when done
                    reader.close()

                    withContext(Dispatchers.Main) {
                        viewModel.setStatus("Scan complete")
                    }
                } else {
                    Log.e(TAG, "Failed to open USB device")
                    withContext(Dispatchers.Main) {
                        viewModel.setStatus("Failed to open USB device")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning device", e)
                withContext(Dispatchers.Main) {
                    viewModel.setStatus("Error: ${e.message}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: FileBrowserViewModel) {
    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val status by viewModel.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VolumeX - APFS File Browser") },
                actions = {
                    IconButton(onClick = {
                        // Refresh - scan again
                        viewModel.setStatus("Refreshing...")
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(status)
                    }
                }
                files.isEmpty() -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text("📱 No files found")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Connect an APFS drive via USB OTG",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Column {
                        Text(
                            "Found ${files.size} files",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LazyColumn {
                            items(files) { file ->
                                FileListItem(file)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileListItem(file: ApfsFileEntry) {
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = {
            Text(
                if (file.isDirectory) "📁 Directory" else "📄 File • ${file.fileSize} bytes"
            )
        }
    )
    Divider()
}