package com.fatalpuppet.volumex.storage.scsi

import android.util.Log
import com.fatalpuppet.volumex.storage.usb.UsbUasTransport

class UasExecutor(
    private val transport: UsbUasTransport
) {

    private var nextTag = 1

    fun testUnitReady(): Boolean {

        val tag = nextTag++

        Log.i(
            "VolumeX",
            "UAS TEST UNIT READY: tag=$tag"
        )

        val cdb = ScsiTestUnitReady.command()

        val commandIU = UasCommandIU.build(
            tag = tag,
            lun = 0,
            cdb = cdb
        )

        Log.d(
            "VolumeX",
            "UAS Command IU = ${
                commandIU.joinToString(" ") {
                    "%02X".format(it)
                }
            }"
        )

        val commandResult = transport.sendCommand(
            data = commandIU
        )

        if (!commandResult.success) {
            Log.e(
                "VolumeX",
                "UAS TEST UNIT READY command transfer failed"
            )
            return false
        }

        Log.d(
            "VolumeX",
            "UAS command transferred successfully"
        )

        val statusResult = transport.receiveStatus(
            size = 1024
        )

        if (!statusResult.success || statusResult.data == null) {
            Log.e(
                "VolumeX",
                "UAS status transfer failed"
            )
            return false
        }

        val status = statusResult.data

        Log.d(
            "VolumeX",
            "UAS STATUS IU length=${status.size}"
        )

        Log.d(
            "VolumeX",
            "UAS STATUS IU = ${
                status.joinToString(" ") {
                    "%02X".format(it)
                }
            }"
        )

        /*
         * Do not interpret the status yet.
         *
         * The next step will decode the actual UAS
         * status IU returned by the T7.
         */
        Log.i(
            "VolumeX",
            "UAS status IU received for tag=$tag"
        )

        return true
    }
}