package app.feldkit.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import app.feldkit.permissions.UsbPermissionManager

class UsbBroadcastReceiver(
    private val onDeviceAttached: () -> Unit,
    private val onDeviceDetached: () -> Unit,
    private val onPermissionGranted: (UsbDevice) -> Unit
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?
    ) {
        when (intent?.action) {

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                onDeviceAttached()
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                onDeviceDetached()
            }
            UsbPermissionManager.ACTION_USB_PERMISSION -> {
                Log.d(
                    "FeldKit",
                    "USB permission broadcast received"
                )
                val device =
                    intent.getParcelableExtra<UsbDevice>(
                        UsbManager.EXTRA_DEVICE
                    )
                val granted =
                    intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                Log.d(
                    "FeldKit",
                    "USB permission granted = $granted"
                )
                if (granted && device != null) {
                    onPermissionGranted(device)
                }
            }
        }
    }
}