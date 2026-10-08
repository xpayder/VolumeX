package com.fatalpuppet.volumex.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fatalpuppet.volumex.provider.DriveFileProvider
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileType
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun FilePreviewScreen(
    entry: FileSystemEntry,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val contentUri = remember(entry.inodeOid, entry.path) {
        DriveFileProvider.buildUri(entry.inodeOid, entry.path)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassWhite8)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(entry.formattedSize, color = TextTertiary, fontSize = 12.sp)
                }
            }

            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                when (entry.fileType) {
                    FileType.IMAGE -> ImagePreview(contentUri, entry.name)
                    FileType.VIDEO -> VideoPreview(contentUri, entry.name)
                    FileType.AUDIO -> AudioPreview(contentUri, entry.name)
                    else -> GenericPreview(entry.name)
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(uri: Uri, name: String) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build(),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun VideoPreview(uri: Uri, name: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val mediaPlayer = remember {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener { mp ->
                isPrepared = true
                duration = mp.duration
                mp.start()
                isPlaying = true
            }
            setOnCompletionListener {
                isPlaying = false
                position = 0
            }
            setOnErrorListener { _, _, _ ->
                error = "Cannot play this video"
                false
            }
            try {
                setDataSource(context, uri)
                prepareAsync()
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            position = mediaPlayer.currentPosition
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.stop()
            mediaPlayer.release()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (error != null) {
            GenericPreview(name, error)
        } else {
            // SurfaceView for video output
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    mediaPlayer.setDisplay(h)
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    mediaPlayer.setDisplay(null)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!isPrepared) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            }

            // Controls
            MediaControls(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                onPlayPause = {
                    if (mediaPlayer.isPlaying) { mediaPlayer.pause(); isPlaying = false }
                    else { mediaPlayer.start(); isPlaying = true }
                },
                onSeek = { ms -> mediaPlayer.seekTo(ms); position = ms }
            )
        }
    }
}

@Composable
private fun AudioPreview(uri: Uri, name: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val mediaPlayer = remember {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener { mp ->
                isPrepared = true
                duration = mp.duration
                mp.start()
                isPlaying = true
            }
            setOnCompletionListener { isPlaying = false; position = 0 }
            setOnErrorListener { _, _, _ -> error = "Cannot play this audio"; false }
            try {
                setDataSource(context, uri)
                prepareAsync()
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            position = mediaPlayer.currentPosition
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer.stop(); mediaPlayer.release() }
    }

    if (error != null) {
        GenericPreview(name, error)
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing album art placeholder
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(
                    Brush.radialGradient(listOf(AccentPurple.copy(alpha = 0.3f), Color.Transparent)),
                    CircleShape
                )
                .background(GlassWhite8, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = AccentPurple, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(32.dp))

        if (!isPrepared) {
            CircularProgressIndicator(color = AccentPurple, modifier = Modifier.size(32.dp))
        } else {
            MediaControls(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                onPlayPause = {
                    if (mediaPlayer.isPlaying) { mediaPlayer.pause(); isPlaying = false }
                    else { mediaPlayer.start(); isPlaying = true }
                },
                onSeek = { ms -> mediaPlayer.seekTo(ms); position = ms }
            )
        }
    }
}

@Composable
private fun MediaControls(
    isPlaying: Boolean,
    position: Int,
    duration: Int,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassWhite8, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { onSeek((it * duration).toInt()) },
            colors = SliderDefaults.colors(
                thumbColor = AccentBlue,
                activeTrackColor = AccentBlue,
                inactiveTrackColor = GlassWhite12
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(position), color = TextTertiary, fontSize = 11.sp)
            Text(formatMs(duration), color = TextTertiary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(56.dp)
                    .background(AccentBlue, CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun GenericPreview(name: String, subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(GlassWhite8, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.InsertDriveFile, null, tint = AccentBlue, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle ?: "No preview available for this file type",
            color = TextTertiary,
            fontSize = 13.sp
        )
    }
}
