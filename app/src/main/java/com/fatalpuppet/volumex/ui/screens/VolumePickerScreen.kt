package com.fatalpuppet.volumex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.theme.*

@Composable
fun VolumePickerScreen(
    volumes: List<VolumeInfo>,
    deviceName: String,
    onSelectVolume: (VolumeInfo, Int) -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy, NavyMid)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassWhite8)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Select Volume", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(deviceName, color = TextTertiary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "${volumes.size} VOLUME${if (volumes.size != 1) "S" else ""} DETECTED",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(volumes) { idx, vol ->
                    VolumeCard(vol = vol, onClick = { onSelectVolume(vol, idx) })
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(vol: VolumeInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassWhite8)
            .border(1.dp, GlassBorderFaint, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(AccentBlue.copy(alpha = 0.3f), AccentPurple.copy(alpha = 0.2f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (vol.isEncrypted) Icons.Default.Lock else Icons.Default.Storage,
                contentDescription = null,
                tint = if (vol.isEncrypted) AccentOrange else AccentBlue,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = vol.name.ifEmpty { "Unnamed Volume" },
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                TypeBadge(vol.type)
                if (vol.isEncrypted) {
                    Spacer(Modifier.width(4.dp))
                    TypeBadge("Encrypted", tint = AccentOrange)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = vol.formattedTotalSize,
                color = TextTertiary,
                fontSize = 12.sp
            )
        }

        Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TypeBadge(label: String, tint: androidx.compose.ui.graphics.Color = AccentBlue) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
