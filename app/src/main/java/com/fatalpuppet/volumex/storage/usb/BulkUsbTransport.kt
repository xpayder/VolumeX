package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

class BulkUsbTransport(

    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint

) {

    companion object {
        private const val TAG = "VolumeX"
    }

    fun send(
        data: ByteArray,
        timeout: Int = 3000
    ): BulkTransferResult {

        Log.d(
            TAG,
            "USB OUT: endpoint=0x${
                "%02X".format(bulkOut.address)
            } " +
                    "maxPacket=${bulkOut.maxPacketSize} " +
                    "requested=${data.size}"
        )

        val transferred = connection.bulkTransfer(
            bulkOut,
            data,
            data.size,
            timeout
        )

        Log.d(
            TAG,
            "USB OUT result=$transferred"
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

        Log.d(
            TAG,
            "USB IN: endpoint=0x${
                "%02X".format(bulkIn.address)
            } " +
                    "maxPacket=${bulkIn.maxPacketSize} " +
                    "requested=$size"
        )

        val buffer = ByteArray(size)

        val transferred = connection.bulkTransfer(
            bulkIn,
            buffer,
            size,
            timeout
        )

        Log.d(
            TAG,
            "USB IN result=$transferred"
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

    fun clearBulkOutHalt(): Boolean {
        val r = connection.controlTransfer(0x02, 0x01, 0x00, bulkOut.address, null, 0, 1000)
        Log.d(TAG, "CLEAR_FEATURE(HALT) OUT endpoint=0x%02X result=$r".format(bulkOut.address))
        return r >= 0
    }

    /** Bulk-Only Mass Storage Reset + clear both halts (USB MSC BOT 5.3.4 reset recovery). */
    fun resetRecovery(interfaceNumber: Int): Boolean {
        val r = connection.controlTransfer(0x21, 0xFF, 0, interfaceNumber, null, 0, 2000)
        Log.d(TAG, "BOT mass storage reset iface=$interfaceNumber result=$r")
        clearBulkInHalt()
        clearBulkOutHalt()
        return r >= 0
    }

    fun clearBulkInHalt(): Boolean {
        val result = connection.controlTransfer(
            0x02,       // USB request type: endpoint / host-to-device
            0x01,       // CLEAR_FEATURE
            0x00,       // ENDPOINT_HALT
            bulkIn.address,
            null,
            0,
            1000
        )

        Log.d(
            "VolumeX",
            "CLEAR_FEATURE(HALT) IN endpoint=0x${
                "%02X".format(bulkIn.address)
            } result=$result"
        )

        return result >= 0
    }

}