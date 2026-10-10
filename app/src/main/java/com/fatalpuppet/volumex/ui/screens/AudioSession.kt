package com.fatalpuppet.volumex.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.fatalpuppet.volumex.provider.DriveFileProvider
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * App-wide audio playback that lives outside any screen: tapping an audio file in the browser starts (or toggles)
 * it in place, the folder's audio files form the queue, and playback keeps going while you browse.
 */
object AudioSession {
    private var state: PlayerState? = null
    private var detach: (() -> Unit)? = null
    var queue by mutableStateOf<List<FileSystemEntry>>(emptyList()); private set
    var currentPath by mutableStateOf<String?>(null); private set

    val player: PlayerState? get() = state
    val active: Boolean get() = currentPath != null
    val current: FileSystemEntry? get() = queue.firstOrNull { it.path == currentPath }

    fun play(ctx: Context, entry: FileSystemEntry, files: List<FileSystemEntry>) {
        if (currentPath == entry.path) { state?.toggle(); return }
        val st = state ?: PlayerState(ExoPlayer.Builder(ctx.applicationContext).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            pauseAtEndOfMediaItems = true   // a finished track stops; it never rolls on into the next file
        }).also { state = it; detach = it.attach { id -> if (id != null) currentPath = id } }
        val list = files.ifEmpty { listOf(entry) }
        queue = list
        val idx = list.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        st.error = null
        st.player.setMediaItems(list.map { MediaItem.Builder().setUri(DriveFileProvider.buildUri(it.inodeOid, it.path)).setMediaId(it.path).build() }, idx, 0)
        st.player.prepare(); st.player.play()
        currentPath = entry.path
    }

    fun stop() {
        detach?.invoke(); state?.player?.release()
        state = null; detach = null; currentPath = null; queue = emptyList()
    }
}

/** The file icon tile of the playing row becomes this play / pause button. */
@Composable
fun AudioPlayButton(modifier: Modifier = Modifier) {
    val s = AudioSession.player ?: return
    Box(
        modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(AccentBlue).clickable { s.toggle() },
        contentAlignment = Alignment.Center
    ) {
        if (s.buffering && !s.playing) CircularProgressIndicator(color = DeepNavy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.playing) "Pause" else "Play", tint = DeepNavy, modifier = Modifier.size(24.dp))
    }
}

/** "00:12 / 03:40" for the row's second line. */
@Composable
fun AudioTimeText(modifier: Modifier = Modifier) {
    val s = AudioSession.player ?: return
    LaunchedEffect(s) { while (isActive) { s.position = s.player.currentPosition; delay(250) } }
    Text("${fmt(s.position)} / ${fmt(s.duration)}", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** Hairline seek bar along the bottom edge of the playing row: tap or drag to seek. */
@Composable
fun AudioSeekLine(modifier: Modifier = Modifier) {
    val s = AudioSession.player ?: return
    var width by remember { mutableIntStateOf(1) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (s.duration > 0) (s.position.toFloat() / s.duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier.fillMaxWidth().height(20.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(s) { detectTapGestures { o -> s.seekTo((o.x / width * s.duration).toLong()) } }
            .pointerInput(s) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { c, _ -> drag = (c.position.x / width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { f -> s.seekTo((f * s.duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x26FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(CircleShape).background(AccentBlue))
    }
}

/** −10 s / +10 s, sized to take the place of the chevron on a folder row. */
@Composable
fun AudioSkipButtons() {
    val s = AudioSession.player ?: return
    Row {
        IconButton(onClick = { s.seekBy(-10_000) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = TextSecondary, modifier = Modifier.size(22.dp)) }
        IconButton(onClick = { s.seekBy(10_000) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = TextSecondary, modifier = Modifier.size(22.dp)) }
    }
}
