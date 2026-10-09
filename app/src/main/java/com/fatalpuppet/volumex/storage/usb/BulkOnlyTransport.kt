package com.fatalpuppet.volumex.storage.usb

import android.util.Log

class BulkOnlyTransport(
    private val transport: BulkUsbTransport
) {
    companion object {
        private const val TAG = "VolumeX"
    }

    /** DATA IN: send CBW, receive data, receive CSW. */
    fun execute(cbw: ByteArray, expectedLength: Int): BulkOnlyResult {
        try {
            Log.d(TAG, "Sending CBW: ${cbw.size} bytes")
            val sendResult = transport.send(cbw)
            if (!sendResult.success) {
                return BulkOnlyResult(false, null, "CBW send failed")
            }

            var data: ByteArray? = null
            if (expectedLength > 0) {
                val receiveResult = transport.receive(expectedLength)
                if (!receiveResult.success) {
                    // Data-in STALL (device rejected the command): clear halt, then the
                    // CSW follows and carries the failure status (USB MSC BOT 6.7.2).
                    Log.w(TAG, "Data-in stalled, clearing halt and reading CSW")
                    transport.clearBulkInHalt()
                } else {
                    data = receiveResult.data
                }
            }

            var cswResult = transport.receive(13)
            if (!cswResult.success) {
                // Second chance after a halt, then give up (caller may reset-recover).
                transport.clearBulkInHalt()
                cswResult = transport.receive(13)
                if (!cswResult.success) {
                    return BulkOnlyResult(false, null, "CSW receive failed")
                }
            }

            val csw = cswResult.data ?: return BulkOnlyResult(false, null, "No CSW data")
            val status = csw[12]
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

    /** DATA OUT: send CBW, send dataOut, receive CSW. */
    fun executeDataOut(cbw: ByteArray, dataOut: ByteArray): BulkOnlyResult {
        try {
            Log.d(TAG, "Sending WRITE CBW: ${cbw.size} bytes")
            val sendCbw = transport.send(cbw)
            if (!sendCbw.success) {
                return BulkOnlyResult(false, null, "WRITE CBW send failed")
            }

            Log.d(TAG, "Sending data OUT: ${dataOut.size} bytes")
            val sendData = transport.send(dataOut)
            if (!sendData.success) {
                return BulkOnlyResult(false, null, "WRITE data send failed")
            }

            val cswResult = transport.receive(13)
            if (!cswResult.success) {
                return BulkOnlyResult(false, null, "WRITE CSW receive failed")
            }

            val csw = cswResult.data ?: return BulkOnlyResult(false, null, "No WRITE CSW data")
            val status = csw[12]
            Log.d(TAG, "WRITE CSW status: $status")

            return if (status == 0x00.toByte()) {
                BulkOnlyResult(true, null, "WRITE OK")
            } else {
                BulkOnlyResult(false, null, "WRITE CSW status = $status")
            }

        } catch (e: Exception) {
            Log.e(TAG, "BOT executeDataOut exception", e)
            return BulkOnlyResult(false, null, "Exception: ${e.message}")
        }
    }

    fun resetRecovery(interfaceNumber: Int): Boolean = transport.resetRecovery(interfaceNumber)

    fun clearHalt(endpoint: android.hardware.usb.UsbEndpoint): Boolean {
        return transport.clearBulkInHalt()
    }
}

data class BulkOnlyResult(
    val success: Boolean,
    val data: ByteArray?,
    val message: String
) {
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
