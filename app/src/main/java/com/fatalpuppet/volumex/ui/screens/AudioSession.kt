package com.fatalpuppet.volumex.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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

/** Slim transport block shown under the playing file (list view) or under the header (grid view). */
@Composable
fun AudioMiniControls(modifier: Modifier = Modifier, showTitle: Boolean = false) {
    val s = AudioSession.player ?: return
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }
    LaunchedEffect(s) { while (isActive) { s.position = s.player.currentPosition; delay(250) } }
    val shown = if (dragging) dragPos else s.position.toFloat()
    val hasPrev = s.player.hasPreviousMediaItem(); val hasNext = s.player.hasNextMediaItem()
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE60F151C)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (showTitle) Text(AudioSession.current?.name ?: "", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        if (s.error != null) Text(s.error!!, color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(shown.toLong()), color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
            Slider(
                value = shown.coerceIn(0f, s.duration.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = true; dragPos = it }, onValueChangeFinished = { s.seekTo(dragPos.toLong()); dragging = false },
                valueRange = 0f..s.duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f).height(28.dp),
                colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = AccentBlue, inactiveTrackColor = Color(0x40FFFFFF))
            )
            Text(fmt(s.duration), color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text(
                    "${if (s.speed % 1f == 0f) s.speed.toInt().toString() else s.speed.toString()}x", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1FFFFFFF)).clickable { speedMenu = true }.padding(horizontal = 9.dp, vertical = 6.dp)
                )
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, modifier = Modifier.background(DarkCard)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp -> DropdownMenuItem(text = { Text("${sp}x", color = if (sp == s.speed) AccentBlue else TextPrimary) }, onClick = { s.changeSpeed(sp); speedMenu = false }) }
                }
            }
            IconButton(onClick = { s.player.seekToPreviousMediaItem() }, enabled = hasPrev, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", tint = if (hasPrev) TextPrimary else TextDisabled) }
            IconButton(onClick = { s.seekBy(-10_000) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Replay10, "Back 10 s", tint = TextPrimary) }
            Box(Modifier.size(42.dp).clip(CircleShape).background(AccentBlue).clickable { s.toggle() }, contentAlignment = Alignment.Center) {
                if (s.buffering && !s.playing) CircularProgressIndicator(color = DeepNavy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.playing) "Pause" else "Play", tint = DeepNavy)
            }
            IconButton(onClick = { s.seekBy(10_000) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Forward10, "Forward 10 s", tint = TextPrimary) }
            IconButton(onClick = { s.player.seekToNextMediaItem() }, enabled = hasNext, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.SkipNext, "Next", tint = if (hasNext) TextPrimary else TextDisabled) }
            IconButton(onClick = { AudioSession.stop() }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Close, "Stop", tint = TextSecondary) }
        }
    }
}
