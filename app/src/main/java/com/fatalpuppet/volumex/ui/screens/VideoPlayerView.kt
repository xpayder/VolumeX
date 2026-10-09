package com.fatalpuppet.volumex.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun fmt(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Video player on top of [MediaPlayer] with a seekable drive-backed Uri: seek bar, +/-10 s,
 * previous/next, playback speed and a tap-to-toggle control overlay.
 */
@Composable
fun VideoPlayerView(
    uri: Uri,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var prepared by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableStateOf(0) }
    var duration by remember(uri) { mutableStateOf(0) }
    var dragging by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var showControls by remember(uri) { mutableStateOf(true) }
    var speed by remember(uri) { mutableStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var aspect by remember(uri) { mutableStateOf(16f / 9f) }
    var touch by remember(uri) { mutableStateOf(0) }

    val mp = remember(uri) {
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setOnPreparedListener { p -> prepared = true; duration = p.duration; p.start(); isPlaying = true }
            setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
            setOnCompletionListener { isPlaying = false; showControls = true }
            setOnErrorListener { _, _, _ -> error = "This video cannot be played"; true }
            try { setDataSource(context, uri); prepareAsync() } catch (e: Exception) { error = e.message ?: "Cannot open video" }
        }
    }
    DisposableEffect(mp) { onDispose { try { mp.stop() } catch (_: Exception) {}; mp.release() } }

    LaunchedEffect(isPlaying, dragging) { while (isPlaying && !dragging && isActive) { position = mp.currentPosition; delay(250) } }
    LaunchedEffect(isPlaying, showControls, touch) { if (isPlaying && showControls) { delay(3500); showControls = false } }

    fun seekBy(d: Int) { if (prepared) { val t = (mp.currentPosition + d).coerceIn(0, duration); mp.seekTo(t); position = t; touch++ } }
    fun togglePlay() {
        if (!prepared) return
        if (mp.isPlaying) { mp.pause(); isPlaying = false } else { if (position >= duration - 300) { mp.seekTo(0) }; mp.start(); isPlaying = true }
        touch++
    }

    Box(modifier.background(Color.Black)) {
        if (error != null) {
            Text(error!!, color = TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { mp.setDisplay(h) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { try { mp.setDisplay(null) } catch (_: Exception) {} }
                        })
                    }
                },
                modifier = Modifier.align(Alignment.Center).aspectRatio(aspect).fillMaxWidth()
            )
            Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showControls = !showControls; touch++ })
            if (!prepared) CircularProgressIndicator(color = AccentBlue, modifier = Modifier.align(Alignment.Center))

            if (showControls || !isPlaying) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                        onValueChange = { dragging = true; position = it.toInt(); touch++ },
                        onValueChangeFinished = { if (prepared) mp.seekTo(position); dragging = false },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = AccentBlue, inactiveTrackColor = Color(0x40FFFFFF))
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(position), color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("  ·  ${fmt(duration)}", color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Box {
                            Text(
                                "${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x", color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { speedMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, modifier = Modifier.background(DarkCard)) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                                    DropdownMenuItem(text = { Text("${sp}x", color = if (sp == speed) AccentBlue else TextPrimary) }, onClick = {
                                        speed = sp; speedMenu = false
                                        try { if (prepared) mp.playbackParams = PlaybackParams().setSpeed(sp).also { if (!mp.isPlaying) mp.pause() } } catch (_: Exception) {}
                                    })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) { Icon(Icons.Default.SkipPrevious, "Previous", tint = if (onPrev != null) TextPrimary else TextDisabled, modifier = Modifier.size(30.dp)) }
                        IconButton(onClick = { seekBy(-10_000) }) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = TextPrimary, modifier = Modifier.size(30.dp)) }
                        Box(Modifier.size(60.dp).clip(CircleShape).background(TextPrimary).clickable { togglePlay() }, contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = DeepNavy, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = { seekBy(10_000) }) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = TextPrimary, modifier = Modifier.size(30.dp)) }
                        IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Icon(Icons.Default.SkipNext, "Next", tint = if (onNext != null) TextPrimary else TextDisabled, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
        }
    }
}
