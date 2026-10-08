package com.fatalpuppet.volumex.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fatalpuppet.volumex.R
import com.fatalpuppet.volumex.storage.FileOperationManager
import com.fatalpuppet.volumex.storage.TransferProgress
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that runs file transfers so the user can browse while files copy.
 *
 * Bind to this service from MainActivity/FileBrowserScreen, then call enqueueTransfer().
 * Transfer progress is delivered via the onProgress callback and displayed as a notification.
 */
class TransferService : Service() {

    companion object {
        private const val TAG = "VolumeX"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "transfers"
    }

    inner class TransferBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    private val binder = TransferBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var activeJobs = 0

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Transfer service started"))
        Log.i(TAG, "TransferService: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TransferService: destroyed")
    }

    /**
     * Enqueue a file copy from the USB drive to the device's Downloads folder.
     *
     * @param reader     Active filesystem reader.
     * @param entry      The file to copy.
     * @param onProgress Progress callback (called from a background thread).
     */
    fun enqueueTransfer(
        reader: FileSystemReader,
        entry: FileSystemEntry,
        onProgress: (TransferProgress) -> Unit = {}
    ): Job {
        activeJobs++
        updateNotification("Transferring ${entry.name}…")

        val manager = FileOperationManager(this)
        return scope.launch {
            try {
                manager.copyToAndroid(reader, entry) { progress ->
                    onProgress(progress)
                    val pct = progress.progressPercent
                    updateNotification("${entry.name} — $pct%")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transfer error for ${entry.name}", e)
                onProgress(TransferProgress(entry.name, 0, entry.size, isComplete = true, error = e.message))
            } finally {
                activeJobs--
                if (activeJobs <= 0) {
                    activeJobs = 0
                    updateNotification("All transfers complete")
                }
            }
        }
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VolumeX file transfer progress"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VolumeX Transfer")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun updateNotification(text: String) {
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }
}
