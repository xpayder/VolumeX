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

        private var holders = 0
        @Volatile private var instance: TransferService? = null
        @Volatile private var stopWhenReady = false

        /** Keeps the process (and the USB session) alive while a copy runs, even with the screen off or the app in the background. */
        @Synchronized fun begin(ctx: android.content.Context) {
            if (holders++ == 0) {
                stopWhenReady = false
                try { androidx.core.content.ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, TransferService::class.java)) } catch (e: Exception) { Log.w(TAG, "cannot start transfer service", e) }
            }
        }

        /** Stops the service once the last copy is done. A stop that arrives before the service called startForeground() waits for it. */
        @Synchronized fun end(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context) {
            if (holders > 0 && --holders == 0) {
                val s = instance
                if (s != null) s.stopSelf() else stopWhenReady = true
            }
        }
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
        startForeground(NOTIF_ID, buildNotification("Transferring files - keep the drive connected"))
        instance = this
        Log.i(TAG, "TransferService: created")
        if (stopWhenReady) { stopWhenReady = false; stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        instance = null
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
