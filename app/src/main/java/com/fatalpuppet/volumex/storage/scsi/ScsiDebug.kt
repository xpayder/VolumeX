package com.fatalpuppet.volumex.storage.scsi

import android.util.Log
object ScsiDebug {
    private const val TAG = "VolumeX"
    fun transaction(
        tx: ScsiTransaction
    ) {
        Log.i(TAG,"==============================")
        Log.i(TAG,tx.command)
        Log.i(TAG,"Success : ${tx.success}")
        Log.i(TAG,"Elapsed : ${tx.elapsedMs} ms")
        Log.i(TAG,"Message : ${tx.message}")
        Log.i(TAG,"==============================")
        ScsiTransactionHistory.add(tx)
    }
}