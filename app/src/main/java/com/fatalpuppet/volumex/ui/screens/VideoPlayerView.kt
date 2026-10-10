package com.fatalpuppet.volumex.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.fatalpuppet.volumex.ui.components.Zoomable
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** ExoPlayer plus the bits of its state the UI needs, as Compose state. */
@Stable
class PlayerState(val player: ExoPlayer) {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var aspect by mutableFloatStateOf(16f / 9f)
    var error by mutableStateOf<String?>(null)
    var speed by mutableFloatStateOf(1f)

    fun toggle() { if (ended) { player.seekTo(0); ended = false }; if (player.isPlaying) player.pause() else player.play() }
    fun seekTo(ms: Long) { player.seekTo(ms.coerceIn(0, duration.coerceAtLeast(0))); position = ms }
    fun seekBy(d: Long) = seekTo(player.currentPosition + d)
    fun changeSpeed(s: Float) { speed = s; player.setPlaybackSpeed(s) }
}

/** Mirrors the ExoPlayer's state into this holder; returns a function that detaches the listener. */
fun PlayerState.attach(onTransition: ((String?) -> Unit)? = null): () -> Unit {
    val st = this
    val l = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { st.playing = isPlaying }
        override fun onPlaybackStateChanged(s: Int) {
            st.buffering = s == Player.STATE_BUFFERING
            st.ended = s == Player.STATE_ENDED
            if (s == Player.STATE_READY) st.duration = st.player.duration.coerceAtLeast(0)
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) { st.duration = 0; st.position = 0; onTransition?.invoke(item?.mediaId) }
        override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) st.aspect = v.width * v.pixelWidthHeightRatio / v.height }
        override fun onPlayerError(e: PlaybackException) { st.error = "This format can't be played inside VolumeX" }
    }
    player.addListener(l)
    return { player.removeListener(l) }
}

@Composable
fun rememberPlayerState(uri: Uri): PlayerState {
    val ctx = LocalContext.current
    val state = remember(uri) {
        val p = newDrivePlayer(ctx).apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true }
        PlayerState(p)
    }
    DisposableEffect(state) {
        val detach = state.attach()
        onDispose { detach(); state.player.release() }
    }
    LaunchedEffect(state) { while (isActive) { state.position = state.player.currentPosition; delay(250) } }
    return state
}

/** Floating control panel shared by the video and audio players. */
@Composable
private fun TransportControls(
    s: PlayerState, dragging: Boolean, onDrag: (Boolean) -> Unit, onPrev: (() -> Unit)?, onNext: (() -> Unit)?,
    modifier: Modifier = Modifier, compact: Boolean = false, onFullscreen: (() -> Unit)? = null, fullscreen: Boolean = false, onInteract: () -> Unit = {}
) {
    var speedMenu by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragPos else s.position.toFloat()
    val big = if (compact) 48.dp else 60.dp
    Column(modifier) {
        Slider(
            value = shown.coerceIn(0f, s.duration.coerceAtLeast(1).toFloat()),
            onValueChange = { onDrag(true); dragPos = it; onInteract() },
            onValueChangeFinished = { s.seekTo(dragPos.toLong()); onDrag(false) },
            valueRange = 0f..s.duration.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = AccentBlue, inactiveTrackColor = Color(0x40FFFFFF))
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(shown.toLong()), color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(fmt(s.duration), color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text(
                    "${if (s.speed % 1f == 0f) s.speed.toInt().toString() else s.speed.toString()}x", color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).clickable { speedMenu = true; onInteract() }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, modifier = Modifier.background(DarkCard)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                        DropdownMenuItem(text = { Text("${sp}x", color = if (sp == s.speed) AccentBlue else TextPrimary) }, onClick = { s.changeSpeed(sp); speedMenu = false })
                    }
                }
            }
            IconButton(onClick = { onPrev?.invoke(); onInteract() }, enabled = onPrev != null) { Icon(Icons.Default.SkipPrevious, "Previous", tint = if (onPrev != null) TextPrimary else TextDisabled) }
            IconButton(onClick = { s.seekBy(-10_000); onInteract() }) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = TextPrimary) }
            Box(
                Modifier.size(big).clip(CircleShape).background(Brush.linearGradient(listOf(AccentBlue, Color(0xFF7AA8FF)))).clickable { s.toggle(); onInteract() },
                contentAlignment = Alignment.Center
            ) {
                if (s.buffering && !s.playing) CircularProgressIndicator(color = DeepNavy, strokeWidth = 2.5.dp, modifier = Modifier.size(big * 0.5f))
                else Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.playing) "Pause" else "Play", tint = DeepNavy, modifier = Modifier.size(big * 0.58f))
            }
            IconButton(onClick = { s.seekBy(10_000); onInteract() }) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = TextPrimary) }
            IconButton(onClick = { onNext?.invoke(); onInteract() }, enabled = onNext != null) { Icon(Icons.Default.SkipNext, "Next", tint = if (onNext != null) TextPrimary else TextDisabled) }
            if (onFullscreen != null) IconButton(onClick = onFullscreen) { Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = TextPrimary) }
            else Spacer(Modifier.width(48.dp))
        }
    }
}

/** Video player (ExoPlayer: mp4, mkv, webm, mov, 3gp, ts, ogv…) with pinch / double-tap zoom, seek bar and speed. */
@Composable
fun VideoPlayerView(
    uri: Uri,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    fullscreen: Boolean = false,
    onOpenExternal: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val s = rememberPlayerState(uri)
    var controls by remember(uri) { mutableStateOf(true) }
    var dragging by remember(uri) { mutableStateOf(false) }
    var touch by remember(uri) { mutableIntStateOf(0) }
    LaunchedEffect(s.playing, controls, touch, dragging) { if (s.playing && controls && !dragging) { delay(3500); controls = false } }

    Box(modifier.background(Color.Black)) {
        if (s.error != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = AccentOrange, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(s.error!!, color = TextSecondary, fontSize = 14.sp)
                if (onOpenExternal != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onOpenExternal) { Text("Open with another app") } }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxAspect = maxWidth / maxHeight
                Zoomable(Modifier.fillMaxSize(), onTap = { controls = !controls; touch++ }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx -> TextureView(ctx).also { s.player.setVideoTextureView(it) } },
                            modifier = Modifier.aspectRatio(s.aspect, matchHeightConstraintsFirst = s.aspect < boxAspect)
                        )
                    }
                }
            }
            if (s.buffering && !s.playing) CircularProgressIndicator(color = AccentBlue, modifier = Modifier.align(Alignment.Center))
            AnimatedVisibility(
                visible = controls || !s.playing, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }
            ) {
                TransportControls(
                    s, dragging, { dragging = it }, onPrev, onNext,
                    Modifier.padding(12.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xD90B1016)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(26.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    onFullscreen = onFullscreen, fullscreen = fullscreen, onInteract = { touch++ }
                )
            }
        }
    }
}

/** Compact audio player: small cover (embedded art when present), title/artist and one slim control block. */
@Composable
fun AudioPlayerCard(uri: Uri, name: String, sizeText: String, onPrev: (() -> Unit)?, onNext: (() -> Unit)?, onOpenExternal: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val s = rememberPlayerState(uri)
    var dragging by remember(uri) { mutableStateOf(false) }
    var art by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var title by remember(uri) { mutableStateOf<String?>(null) }
    var artist by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(ctx, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                r.embeddedPicture?.let { b -> art = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }
                r.release()
            } catch (_: Exception) {}
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).widthIn(max = 460.dp)
            .clip(RoundedCornerShape(28.dp)).background(Color(0xE60F151C)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(AccentPurple.copy(0.55f), AccentBlue.copy(0.45f)))),
                contentAlignment = Alignment.Center
            ) {
                val a = art
                if (a != null) Image(a.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: name.substringBeforeLast('.'), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(artist ?: "$sizeText · ${name.substringAfterLast('.', "").uppercase()}", color = TextTertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (s.error != null) {
            Text(s.error!!, color = AccentOrange, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            if (onOpenExternal != null) Button(onClick = onOpenExternal) { Text("Open with another app") }
        } else TransportControls(s, dragging, { dragging = it }, onPrev, onNext, Modifier.fillMaxWidth(), compact = true)
    }
}
