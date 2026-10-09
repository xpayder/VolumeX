package com.fatalpuppet.volumex.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fatalpuppet.volumex.provider.DriveFileProvider
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.ui.theme.AccentOrange
import com.fatalpuppet.volumex.ui.theme.DeepNavy
import com.fatalpuppet.volumex.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

private object ThumbCache : LruCache<String, Bitmap>(48) // frames are small (<= 360 px)
private val gate = Semaphore(2)                           // keep USB contention low while a grid scrolls

/** A frame from the video, decoded straight from the drive; falls back to a play icon. */
@Composable
fun VideoThumb(entry: FileSystemEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = entry.path + "#" + entry.size
    val frame by produceState<Bitmap?>(ThumbCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                gate.acquire()
                try {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, DriveFileProvider.buildUri(entry.inodeOid, entry.path))
                        r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                            ?: r.getFrameAtTime(0)
                    } finally { try { r.release() } catch (_: Exception) {} }
                } catch (e: Exception) { null } finally { gate.release() }
            }?.also { ThumbCache.put(key, it) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val f = frame
        if (f != null) {
            Image(f.asImageBitmap(), entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Default.PlayCircle, null, tint = TextPrimary.copy(alpha = 0.92f), modifier = Modifier.size(34.dp))
        } else {
            Icon(Icons.Default.PlayCircle, null, tint = AccentOrange, modifier = Modifier.size(40.dp))
        }
    }
}
