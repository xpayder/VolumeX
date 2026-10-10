package com.fatalpuppet.volumex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.theme.*

@Composable
fun DeviceVolumeCard(
    volumeInfo: VolumeInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        elevated = true
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Volume icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Accent.copy(alpha = 0.3f), AccentSky.copy(alpha = 0.15f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = volumeInfo.name.ifBlank { "Unnamed Volume" },
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VolumeTypeBadge(volumeInfo.type)
                        if (volumeInfo.isEncrypted) {
                            Spacer(Modifier.width(6.dp))
                            EncryptedBadge()
                        }
                    }
                }

                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowForwardIos,
                        contentDescription = "Browse",
                        tint = Accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(label = "Files", value = "${volumeInfo.numFiles}")
                StatItem(label = "Folders", value = "${volumeInfo.numDirectories}")
                StatItem(label = "Total", value = volumeInfo.formattedTotalSize)
                if (volumeInfo.freeBlocks > 0) {
                    StatItem(label = "Free", value = volumeInfo.formattedFreeSize)
                }
            }

            // Usage bar
            if (volumeInfo.totalBlocks > 0 && volumeInfo.freeBlocks > 0) {
                Spacer(Modifier.height(14.dp))
                val usedFraction = 1f - (volumeInfo.freeBlocks.toFloat() / volumeInfo.totalBlocks.toFloat())
                StorageBar(usedFraction)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(text = label, color = TextTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun VolumeTypeBadge(type: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Accent.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = type, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EncryptedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AccentOrange.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = AccentOrange,
            modifier = Modifier.size(10.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(text = "Encrypted", color = AccentOrange, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StorageBar(usedFraction: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Storage", color = TextTertiary, fontSize = 11.sp)
            Text("${(usedFraction * 100).toInt()}% used", color = TextTertiary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GlassWhite8)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedFraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Accent, AccentSky))
                    )
            )
        }
    }
}
