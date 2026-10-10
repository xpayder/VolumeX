package com.fatalpuppet.volumex.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fatalpuppet.volumex.ui.components.Zoomable
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** libVLC player state for files ExoPlayer cannot decode on this phone (software decoding of ProRes, DNxHD, MXF, WMV, FLV, FFV1…). */
@Stable
class VlcState(ctx: Context, private val uri: Uri) : TransportState() {
    private val main = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(ctx.applicationContext, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
    val player = MediaPlayer(libVlc)
    private var pfd: ParcelFileDescriptor? = null
    private var released = false

    init {
        player.setEventListener { ev ->
            main.post {
                if (released) return@post
                when (ev.type) {
                    MediaPlayer.Event.Playing -> { playing = true; buffering = false; ended = false }
                    MediaPlayer.Event.Paused -> playing = false
                    MediaPlayer.Event.Stopped -> playing = false
                    MediaPlayer.Event.EndReached -> { playing = false; ended = true }
                    MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                    MediaPlayer.Event.TimeChanged -> position = ev.timeChanged
                    MediaPlayer.Event.LengthChanged -> duration = ev.lengthChanged
                    MediaPlayer.Event.EncounteredError -> error = "This format can't be decoded on this phone"
                }
            }
        }
        try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            val media = Media(libVlc, pfd!!.fileDescriptor)
            player.media = media
            media.release()
        } catch (e: Exception) { error = e.message ?: "Cannot open the file" }
    }

    private var started = false

    /** Attaches the video surface; playback starts once the view has a real size (libVLC rejects a zero-size surface). */
    fun attach(view: VLCVideoLayout) {
        player.attachViews(view, null, true, false)
        view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (w > 0 && h > 0 && !released) {
                player.vlcVout.setWindowSize(w, h)
                player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                if (!started && error == null) { started = true; player.play() }
            }
        }
    }

    override fun toggle() {
        if (ended) { player.stop(); player.play(); ended = false; return }
        if (player.isPlaying) player.pause() else player.play()
    }
    override fun seekTo(ms: Long) { player.time = ms.coerceIn(0, duration.coerceAtLeast(0)); position = ms }
    override fun changeSpeed(s: Float) { speed = s; player.rate = s }

    fun release() {
        if (released) return
        released = true
        try { player.setEventListener(null); player.stop(); player.detachViews(); player.release(); libVlc.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}

@Composable
fun VlcVideoPlayerView(
    uri: Uri,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onFullscreen: (() -> Unit)?,
    fullscreen: Boolean,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val s = remember(uri) { VlcState(ctx, uri) }
    DisposableEffect(s) { onDispose { s.release() } }
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
            AndroidView(factory = { c -> VLCVideoLayout(c).also { s.attach(it) } }, modifier = Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { controls = !controls; touch++ })
            if (s.buffering && !s.playing) CircularProgressIndicator(color = AccentBlue, modifier = Modifier.align(Alignment.Center))
            Text(
                "SOFTWARE DECODER", color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x990B1016)).padding(horizontal = 8.dp, vertical = 4.dp)
            )
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
