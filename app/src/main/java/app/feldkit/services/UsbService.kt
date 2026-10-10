package app.feldkit.services

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.content.Intent

import app.feldkit.permissions.UsbPermissionManager
import app.feldkit.data.models.UsbDeviceInfo

class UsbService(
    private val context: Context
) {
    private val permissionManager =
        UsbPermissionManager(context)
    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var receiver: BroadcastReceiver? = null

    fun hasPermission(
        device: UsbDevice
    ): Boolean {
        return permissionManager.hasPermission(device)
    }
    fun requestPermission(
        device: UsbDevice
    ) {
        permissionManager.requestPermission(device)
    }

    fun isUsbHostSupported(): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_USB_HOST
        )
    }
    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun getDeviceInfo(): List<UsbDeviceInfo> {
        return usbManager.deviceList.values.map { device ->
            UsbDeviceInfo(
                deviceName = device.deviceName,
                manufacturer = device.manufacturerName,
                productName = device.productName,
                serialNumber = try {
                    device.serialNumber
                } catch (_: SecurityException) {
                    null
                },
                vendorId = device.vendorId,
                productId = device.productId,
                permissionGranted =
                    permissionManager.hasPermission(device)
            )
        }
    }

    fun getConnectedDeviceCount(): Int {
        return usbManager.deviceList.size
    }

    fun hasConnectedDevices(): Boolean {
        return usbManager.deviceList.isNotEmpty()
    }
    fun registerReceiver(
        onAttach: () -> Unit,
        onDetach: () -> Unit,
        onPermissionGranted: (UsbDevice) -> Unit
    ) {
        receiver = UsbBroadcastReceiver(
            onAttach,
            onDetach,
            onPermissionGranted
        )
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPermissionManager.ACTION_USB_PERMISSION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(
                receiver,
                filter
            )
            Log.d(
                "FeldKit",
                "USB receiver registered"
            )
        }
    }
    fun unregisterReceiver() {
        receiver?.let {
            context.unregisterReceiver(it)
        }
    }
}