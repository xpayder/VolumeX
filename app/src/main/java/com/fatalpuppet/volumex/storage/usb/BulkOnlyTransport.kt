package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbEndpoint
import com.fatalpuppet.volumex.storage.scsi.CommandStatusWrapperParser
import com.fatalpuppet.volumex.storage.scsi.ScsiResponseValidator
import com.fatalpuppet.volumex.storage.scsi.ScsiResult

import android.util.Log

class BulkOnlyTransport(
    private val transport: BulkUsbTransport
) {
    companion object {
        private const val TAG = "VolumeX"
    }

    fun execute(cbw: ByteArray, expectedLength: Int): BulkOnlyResult {
        try {
            // Send CBW
            Log.d(TAG, "Sending CBW: ${cbw.size} bytes")
            val sendResult = transport.send(cbw)
            if (!sendResult.success) {
                return BulkOnlyResult(false, null, "CBW send failed")
            }

            // Receive data (if expectedLength > 0)
            var data: ByteArray? = null
            if (expectedLength > 0) {
                val receiveResult = transport.receive(expectedLength)
                if (!receiveResult.success) {
                    // Try to recover: clear halt on IN endpoint
                    Log.w(TAG, "Data receive failed, attempting recovery...")
                    transport.clearHalt(transport.bulkIn)

                    // Try once more
                    val retryResult = transport.receive(expectedLength)
                    if (!retryResult.success) {
                        return BulkOnlyResult(false, null, "DATA transfer failed after recovery")
                    }
                    data = retryResult.data
                } else {
                    data = receiveResult.data
                }
            }

            // Receive CSW (13 bytes)
            val cswResult = transport.receive(13)
            if (!cswResult.success) {
                return BulkOnlyResult(false, null, "CSW receive failed")
            }

            // Parse CSW to check status
            val csw = cswResult.data ?: return BulkOnlyResult(false, null, "No CSW data")
            val status = csw[12] // Status is at byte 12

            Log.d(TAG, "CSW status: $status")

            if (status != 0x00.toByte()) {
                return BulkOnlyResult(false, data, "CSW status = $status")
            }

            return BulkOnlyResult(true, data, "OK")

        } catch (e: Exception) {
            Log.e(TAG, "BOT execute exception", e)
            return BulkOnlyResult(false, null, "Exception: ${e.message}")
        }
    }

    // Add this function to BulkUsbTransport.kt
    fun clearHalt(endpoint: UsbEndpoint): Boolean {
        return try {
            val result = connection?.clearHalt(endpoint) ?: false
            Log.d(TAG, "CLEAR_FEATURE(HALT) on endpoint ${endpoint.address} result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Clear halt failed", e)
            false
        }
    }
}

data class BulkOnlyResult(
    val success: Boolean,
    val data: ByteArray?,
    val message: String
) {
    // In BulkUsbTransport.kt, update the send function
    fun send(data: ByteArray): BulkTransferResult {
        try {
            val result = connection?.bulkTransfer(bulkOut, data, data.size, TIMEOUT_MS)
            Log.d(TAG, "USB OUT: endpoint=${bulkOut.address} maxPacket=${bulkOut.maxPacketSize} requested=${data.size} result=$result")

            return if (result != null && result >= 0) {
                BulkTransferResult(true, "Sent $result bytes")
            } else {
                BulkTransferResult(false, "Transfer failed with result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB OUT exception", e)
            return BulkTransferResult(false, "Exception: ${e.message}")
        }
    }

    // Update receive function
    fun receive(length: Int): BulkTransferResult {
        try {
            val buffer = ByteArray(length)
            val result = connection?.bulkTransfer(bulkIn, buffer, length, TIMEOUT_MS)
            Log.d(TAG, "USB IN: endpoint=${bulkIn.address} maxPacket=${bulkIn.maxPacketSize} requested=$length result=$result")

            return if (result != null && result >= 0) {
                if (result < length) {
                    // Truncate to actual received size
                    BulkTransferResult(true, buffer.copyOf(result))
                } else {
                    BulkTransferResult(true, buffer)
                }
            } else {
                BulkTransferResult(false, "Transfer failed with result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB IN exception", e)
            return BulkTransferResult(false, "Exception: ${e.message}")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BulkOnlyResult
        if (success != other.success) return false
        if (data != null) {
            if (other.data == null) return false
            if (!data.contentEquals(other.data)) return false
        } else if (other.data != null) return false
        if (message != other.message) return false
        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + message.hashCode()
        return result
    }
}