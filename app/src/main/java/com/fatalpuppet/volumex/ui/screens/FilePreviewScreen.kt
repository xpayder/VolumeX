package com.fatalpuppet.volumex.ui.screens

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.hazeSource
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilePreviewScreen(
    entry: FileSystemEntry,
    siblings: List<FileSystemEntry> = listOf(entry),
    canDelete: Boolean = false,
    onDelete: (FileSystemEntry) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val items = remember(entry, siblings) { if (siblings.any { it.path == entry.path }) siblings else listOf(entry) }
    val startIndex = remember(entry, items) { items.indexOfFirst { it.path == entry.path }.coerceAtLeast(0) }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = startIndex) { items.size }
    var mediaIndex by remember(entry) { mutableStateOf(startIndex) }
    val isImage = entry.fileType == FileType.IMAGE
    val current = if (isImage) items[pager.currentPage.coerceIn(0, items.lastIndex)] else items[mediaIndex.coerceIn(0, items.lastIndex)]
    val position = if (isImage) pager.currentPage else mediaIndex
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    // landscape + hidden system bars while a video is fullscreen
    val activity = context as? android.app.Activity
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    val hazeState = dev.chrisbanes.haze.rememberHazeState()
    Box(modifier = Modifier.fillMaxSize().background(DeepNavy)) {
        var headerPx by remember { mutableIntStateOf(0) }
        val headerDp = with(androidx.compose.ui.platform.LocalDensity.current) { headerPx.toDp() }
        if (!fullscreen) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).onSizeChanged { headerPx = it.height }.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).liquidGlass(hazeState, RoundedCornerShape(24.dp)).padding(horizontal = 4.dp, vertical = 6.dp)) {
                IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(current.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (items.size > 1) "${position + 1} of ${items.size}" else current.formattedSize, color = TextTertiary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                if (current.fileType == FileType.VIDEO) IconButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, "Fullscreen", tint = TextSecondary) }
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(current.extension) ?: "*/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, DriveFileProvider.buildUri(current.inodeOid, current.path))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share"))
                }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
                if (canDelete) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = TextSecondary) }
            }
        Column(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {

            if (isImage) {
                androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].path }) { page ->
                    val e = items[page]
                    Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        if (e.fileType == FileType.IMAGE) ImagePreview(DriveFileProvider.buildUri(e.inodeOid, e.path), e.name) else GenericPreview(e.name)
                    }
                }
            } else {
                val uri = remember(current.inodeOid, current.path) { DriveFileProvider.buildUri(current.inodeOid, current.path) }
                when (current.fileType) {
                    FileType.VIDEO -> VideoPlayerView(
                        uri = uri, modifier = Modifier.fillMaxSize().padding(top = if (fullscreen) 0.dp else headerDp),
                        onPrev = if (mediaIndex > 0) ({ mediaIndex-- }) else null,
                        onNext = if (mediaIndex < items.lastIndex) ({ mediaIndex++ }) else null
                    )
                    FileType.AUDIO -> Box(Modifier.fillMaxSize().padding(top = headerDp).padding(12.dp), contentAlignment = Alignment.Center) { AudioPreview(uri, current.name) }
                    else -> Box(Modifier.fillMaxSize().padding(top = headerDp).padding(12.dp), contentAlignment = Alignment.Center) { GenericPreview(current.name) }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete \"${current.name}\"?", color = TextPrimary) },
                text = { Text("This permanently removes it from the drive.", color = TextSecondary) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(current); onNavigateBack() }) { Text("Delete", color = AccentRed) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextTertiary) } },
                containerColor = DarkCard,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            )
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
