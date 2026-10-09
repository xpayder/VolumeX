package com.fatalpuppet.volumex.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.components.*
import com.fatalpuppet.volumex.ui.theme.*
import com.fatalpuppet.volumex.ui.viewmodel.DeviceState
import com.fatalpuppet.volumex.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onBrowseVolume: (VolumeInfo, Int) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {}
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavy)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("VolumeX", color = TextTertiary, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp).weight(1f))
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = TextSecondary) }
            }

            // Content
            AnimatedContent(
                targetState = deviceState,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "deviceState"
            ) { state ->
                when (state) {
                    is DeviceState.Disconnected -> {
                        EmptyBay(note = if (statusMessage == "Safe to unplug") "Safe to unplug" else null)
                    }
                    is DeviceState.Connecting -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentBlue)
                                Spacer(Modifier.height(16.dp))
                                Text("Connecting...", color = TextSecondary)
                            }
                        }
                    }
                    is DeviceState.Connected -> {
                        ConnectedView(
                            deviceName = state.deviceName,
                            state = state,
                            onBrowseVolume = onBrowseVolume,
                            onEject = { viewModel.eject() },
                            onOpenSettings = onOpenSettings
                        )
                    }
                    is DeviceState.NeedsPassphrase -> {
                        EmptyStateView(
                            icon = Icons.Default.UsbOff,
                            title = "Encrypted Volume",
                            subtitle = state.hint,
                            actionLabel = "Scan for Devices"
                        )
                    }
                    is DeviceState.Error -> {
                        EmptyStateView(
                            icon = Icons.Default.Error,
                            title = "Connection Error",
                            subtitle = state.message
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(statusMessage: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassBackground(shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), borderWidth = 0.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(listOf(AccentBlue, AccentPurple))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VolumeX",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusMessage,
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ConnectedView(
    deviceName: String,
    state: DeviceState.Connected,
    onBrowseVolume: (VolumeInfo, Int) -> Unit,
    onEject: () -> Unit,
    onOpenSettings: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(AccentGreen))
                    Spacer(Modifier.width(8.dp))
                    Text("CONNECTED", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Text(deviceName, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${state.volumes.size} volume${if (state.volumes.size != 1) "s" else ""}", color = TextTertiary, fontSize = 13.sp)
            }
        }
        items(state.volumes.size) { idx ->
            val vol = state.volumes[idx]
            val writable = (state.reader as? com.fatalpuppet.volumex.storage.filesystem.CompositeReader)?.writerFor(idx) != null || (state.reader !is com.fatalpuppet.volumex.storage.filesystem.CompositeReader && state.writer != null)
            DriveCard(
                volume = vol, writable = writable, onOpen = { onBrowseVolume(vol, idx) }, onEject = onEject,
                onEnableWrite = if (!writable && vol.type.equals("APFS", true)) onOpenSettings else null
            )
        }
        if (state.volumes.isEmpty()) {
            item { EmptyStateView(icon = Icons.Default.FolderOff, title = "No Volumes Found", subtitle = "The drive was recognised but no readable volume was found on it.") }
        }
        item {
            Text(
                "Eject before unplugging so pending writes are saved.", color = TextTertiary, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private val FormatChips = listOf("APFS", "HFS+", "EXFAT", "FAT32", "FILEVAULT")

@Composable
private fun EmptyBay(note: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        if (note != null) {
            Text(
                "\u2713 ${note.uppercase()}", color = AccentGreen, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 18.dp).clip(RoundedCornerShape(8.dp)).background(AccentGreen.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Text("No drive connected", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Plug a USB drive into your phone to read and write it. It mounts automatically.",
            color = TextTertiary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FormatChips.forEach { c ->
                Text(
                    c, color = TextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(DarkSurface).padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = GlassBorder, cornerRadius = androidx.compose.ui.geometry.CornerRadius(36f * density / 3f * 2.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * density / 2f, pathEffect = dash))
                val w = size.width * 0.34f; val h = size.height * 0.11f
                drawRoundRect(
                    color = AccentBlue, topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * density / 2f)
                )
                val iw = w * 0.5f; val ih = h * 0.28f
                drawRoundRect(
                    color = AccentBlue, topLeft = androidx.compose.ui.geometry.Offset((size.width - iw) / 2, (size.height - ih) / 2), size = androidx.compose.ui.geometry.Size(iw, ih),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(ih / 2)
                )
            }
        }
    }
}
