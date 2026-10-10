package app.feldkit.storage.scsi

import android.util.Log
import app.feldkit.storage.disk.HexDump

object ScsiCommandLogger {

    private const val TAG = "FeldKit"

    fun sent(command: ByteArray) {
        Log.d(
            TAG,
            "TX : ${HexDump.format(command)}"
        )
    }

    fun received(data: ByteArray) {
        Log.d(
            TAG,
            "RX : ${HexDump.format(data)}"
        )
    }

}