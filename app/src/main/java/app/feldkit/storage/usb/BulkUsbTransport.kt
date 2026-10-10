package app.feldkit.storage.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

class BulkUsbTransport(

    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint

) {

    companion object {
        private const val TAG = "FeldKit"
        private const val CHUNK = 16384
        private const val DEPTH = 8
    }

    fun send(
        data: ByteArray,
        timeout: Int = 3000
    ): BulkTransferResult {

        if (UsbTuning.verbose) Log.d(TAG, "USB OUT: endpoint=0x${"%02X".format(bulkOut.address)} maxPacket=${bulkOut.maxPacketSize} requested=${data.size}")

        val transferred = connection.bulkTransfer(
            bulkOut,
            data,
            data.size,
            timeout
        )

        if (UsbTuning.verbose) Log.d(TAG, "USB OUT result=$transferred")

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

        if (UsbTuning.fastReads && size >= 65536) {
            val r = try { receivePipelined(size, timeout) } catch (e: Exception) {
                Log.w(TAG, "pipelined read failed, falling back to synchronous reads", e); UsbTuning.fastReads = false; closePool(); null
            }
            if (r != null) return r
        }
        if (UsbTuning.verbose) Log.d(TAG, "USB IN: endpoint=0x${"%02X".format(bulkIn.address)} maxPacket=${bulkIn.maxPacketSize} requested=$size")

        val buffer = ByteArray(size)

        val transferred = connection.bulkTransfer(
            bulkIn,
            buffer,
            size,
            timeout
        )

        if (UsbTuning.verbose) Log.d(TAG, "USB IN result=$transferred")

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

    // ---- pipelined bulk-in (opt-in): keeps several 16 KB requests queued so the host controller never idles between them ----
    private class Slot(val req: android.hardware.usb.UsbRequest) { val buf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(CHUNK); var off = 0; var len = 0 }
    private var pool: Array<Slot>? = null

    private fun receivePipelined(size: Int, timeout: Int): BulkTransferResult {
        val slots = pool ?: Array(DEPTH) { Slot(android.hardware.usb.UsbRequest().also { r -> if (!r.initialize(connection, bulkIn)) throw java.io.IOException("UsbRequest.initialize failed") }) }.also { pool = it }
        val out = ByteArray(size)
        var queueOff = 0; var outstanding = 0; var received = 0; var ended = false
        fun queue(slot: Slot) {
            if (queueOff >= size) return
            val len = minOf(CHUNK, size - queueOff)
            slot.buf.clear(); slot.buf.limit(len); slot.off = queueOff; slot.len = len
            slot.req.clientData = slot
            if (!slot.req.queue(slot.buf)) throw java.io.IOException("UsbRequest.queue failed")
            queueOff += len; outstanding++
        }
        for (s in slots) queue(s)
        var failed = false
        while (outstanding > 0) {
            val r = connection.requestWait(timeout.toLong())
            if (r == null) { failed = true; break }
            val slot = r.clientData as? Slot ?: continue
            outstanding--
            val got = slot.buf.position()
            if (got > 0) { System.arraycopy(slot.buf.array(), 0, out, slot.off, got); received += got }
            if (got < slot.len) ended = true                       // short packet: the device has nothing more for this command
            if (!ended) queue(slot)
        }
        if (outstanding > 0) {                                      // stop what is still queued before the caller reads the CSW
            for (s in slots) try { s.req.cancel() } catch (_: Exception) {}
            while (outstanding > 0) { connection.requestWait(500L) ?: break; outstanding-- }
        }
        if (failed || received < size) {
            UsbTuning.fastReads = false
            return BulkTransferResult(success = false, bytesTransferred = received, data = null)
        }
        return BulkTransferResult(success = true, bytesTransferred = received, data = out)
    }

    fun closePool() { pool?.forEach { try { it.req.close() } catch (_: Exception) {} }; pool = null }

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
            "FeldKit",
            "CLEAR_FEATURE(HALT) IN endpoint=0x${
                "%02X".format(bulkIn.address)
            } result=$result"
        )

        return result >= 0
    }

}