package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.*

class BulkUsbTransport(

    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint

) {

    fun send(
        data: ByteArray,
        timeout: Int = 3000
    ): BulkTransferResult {

        val transferred = connection.bulkTransfer(
            bulkOut,
            data,
            data.size,
            timeout
        )

        return BulkTransferResult(
            success = transferred >= 0,
            bytesTransferred = transferred,
            data = null
        )

    }

    fun receive(
        size: Int,
        timeout: Int = 3000
    ): BulkTransferResult {

        val buffer = ByteArray(size)

        val transferred = connection.bulkTransfer(
            bulkIn,
            buffer,
            size,
            timeout
        )

        android.util.Log.d(
            "VolumeX",
            "USB bulk IN requested=$size transferred=$transferred"
        )

        return BulkTransferResult(
            success = transferred >= 0,
            bytesTransferred = transferred,
            data =
                if (transferred > 0)
                    buffer.copyOf(transferred)
                else
                    null
        )
    }
}