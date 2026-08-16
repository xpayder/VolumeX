package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

class UsbUasTransport(
    private val connection: UsbDeviceConnection,
    private val uasInterface: UsbUasInterface
) {

    fun sendCommand(
        data: ByteArray,
        timeout: Int = 3000
    ): BulkTransferResult {

        Log.d(
            "VolumeX",
            "UAS CMD OUT: endpoint=0x${"%02X".format(uasInterface.commandOut.address)} " +
                    "requested=${data.size}"
        )

        val transferred = connection.bulkTransfer(
            uasInterface.commandOut,
            data,
            data.size,
            timeout
        )

        Log.d(
            "VolumeX",
            "UAS CMD OUT result=$transferred"
        )

        return BulkTransferResult(
            success = transferred == data.size,
            bytesTransferred = transferred,
            data = null
        )
    }

    fun receiveStatus(
        size: Int = 1024,
        timeout: Int = 3000
    ): BulkTransferResult {

        return receive(
            endpoint = uasInterface.statusIn,
            size = size,
            timeout = timeout,
            label = "STATUS IN"
        )
    }

    fun receiveData(
        size: Int,
        timeout: Int = 3000
    ): BulkTransferResult {

        return receive(
            endpoint = uasInterface.dataIn,
            size = size,
            timeout = timeout,
            label = "DATA IN"
        )
    }

    fun sendData(
        data: ByteArray,
        timeout: Int = 3000
    ): BulkTransferResult {

        Log.d(
            "VolumeX",
            "UAS DATA OUT: endpoint=0x${"%02X".format(uasInterface.dataOut.address)} " +
                    "requested=${data.size}"
        )

        val transferred = connection.bulkTransfer(
            uasInterface.dataOut,
            data,
            data.size,
            timeout
        )

        Log.d(
            "VolumeX",
            "UAS DATA OUT result=$transferred"
        )

        return BulkTransferResult(
            success = transferred == data.size,
            bytesTransferred = transferred,
            data = null
        )
    }

    private fun receive(
        endpoint: UsbEndpoint,
        size: Int,
        timeout: Int,
        label: String
    ): BulkTransferResult {

        val buffer = ByteArray(size)

        Log.d(
            "VolumeX",
            "UAS $label: endpoint=0x${"%02X".format(endpoint.address)} " +
                    "requested=$size"
        )

        val transferred = connection.bulkTransfer(
            endpoint,
            buffer,
            size,
            timeout
        )

        Log.d(
            "VolumeX",
            "UAS $label result=$transferred"
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