package com.fatalpuppet.volumex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo
import com.fatalpuppet.volumex.ui.theme.*

private val Mono = FontFamily.Monospace

/** One mounted volume: name, format, access mode, capacity bar and the primary actions. */
@Composable
fun DriveCard(
    volume: VolumeInfo,
    writable: Boolean,
    onOpen: () -> Unit,
    onEject: () -> Unit,
    onEnableWrite: (() -> Unit)? = null,
    locked: Boolean = false
) {
    val accent = when (volume.type.uppercase()) {
        "APFS" -> Color(0xFF7BA7FF); "HFS+", "HFSX" -> Color(0xFF9B8CFF); "EXFAT" -> Color(0xFF4CD6A8); "FAT32" -> Color(0xFFFFB35C); else -> AccentBlue
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp)).background(DarkSurface).border(1.dp, GlassBorderFaint, RoundedCornerShape(24.dp))
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 18.dp, end = 18.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.08f)))),
                contentAlignment = Alignment.Center
            ) { Icon(if (volume.isEncrypted) Icons.Default.Lock else Icons.Default.Storage, null, tint = accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(volume.name.ifBlank { "Untitled" }, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(volume.type.uppercase(), accent)
                    if (locked) Chip("LOCKED", AccentRed) else {
                        Chip(if (writable) "READ & WRITE" else "READ ONLY", if (writable) AccentGreen else AccentOrange)
                        if (volume.isEncrypted) Chip("FILEVAULT", AccentGreen)
                    }
                }
            }
        }

        if (volume.totalBlocks > 0) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                if (volume.freeKnown) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(volume.formattedFreeSize, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("free of ${volume.formattedTotalSize}", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    val frac = volume.usedFraction
                    val barColor = when { frac > 0.95f -> AccentRed; frac > 0.85f -> AccentOrange; else -> accent }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DarkCard)) {
                        Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.01f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(barColor))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${volume.formattedUsedSize} used", color = TextTertiary, fontSize = 11.sp, fontFamily = Mono)
                } else {
                    Text(volume.formattedTotalSize, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("total capacity", color = TextTertiary, fontSize = 12.sp)
                }
                if (volume.numFiles > 0 || volume.numDirectories > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("${volume.numFiles} files · ${volume.numDirectories} folders", color = TextTertiary, fontSize = 11.sp, fontFamily = Mono)
                }
            }
        } else Spacer(Modifier.height(12.dp))

        if (!locked && !writable && onEnableWrite != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(AccentOrange.copy(alpha = 0.10f))
                    .clickable(onClick = onEnableWrite).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = AccentOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Writing to APFS is experimental and off by default. Tap to enable it in Settings.", color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpen, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = DeepNavy)
            ) {
                Icon(if (locked) Icons.Default.LockOpen else Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (locked) "Unlock" else "Open", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            OutlinedButton(
                onClick = onEject, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary), border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
            ) { Icon(Icons.Default.Eject, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Eject", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 4.dp)
    )
}
