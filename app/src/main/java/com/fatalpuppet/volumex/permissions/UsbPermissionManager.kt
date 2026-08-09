package com.fatalpuppet.volumex.permissions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class UsbPermissionManager(
    private val context: Context
) {
    companion object {
        const val ACTION_USB_PERMISSION =
            "com.fatalpuppet.volumex.USB_PERMISSION"
    }
    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    fun hasPermission(
        device: UsbDevice
    ): Boolean {
        return usbManager.hasPermission(device)
    }
    fun requestPermission(
        device: UsbDevice
    ) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(
                context.packageName
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(
            device,
            intent
        )
    }
}