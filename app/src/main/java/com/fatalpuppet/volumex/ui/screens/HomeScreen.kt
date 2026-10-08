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
    onBrowseVolume: (VolumeInfo, Int) -> Unit = { _, _ -> }
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DeepNavy, DarkNavy, NavyMid)
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            HomeTopBar(statusMessage = statusMessage)

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
                        EmptyStateView(
                            icon = Icons.Default.UsbOff,
                            title = "No Device Connected",
                            subtitle = "Connect an APFS or HFS+ drive via USB OTG cable to get started",
                            actionLabel = "Scan for Devices"
                        )
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
                            volumes = state.volumes,
                            onBrowseVolume = onBrowseVolume
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
    volumes: List<VolumeInfo>,
    onBrowseVolume: (VolumeInfo, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            // Device connected header
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Connected",
                        color = AccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = deviceName,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${volumes.size} VOLUME${if (volumes.size != 1) "S" else ""}",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        items(volumes.size) { idx ->
            DeviceVolumeCard(
                volumeInfo = volumes[idx],
                onClick = { onBrowseVolume(volumes[idx], idx) }
            )
        }

        if (volumes.isEmpty()) {
            item {
                EmptyStateView(
                    icon = Icons.Default.FolderOff,
                    title = "No Volumes Found",
                    subtitle = "The connected drive has no APFS or HFS+ volumes"
                )
            }
        }
    }
}
