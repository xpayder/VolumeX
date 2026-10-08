package com.fatalpuppet.volumex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileType
import com.fatalpuppet.volumex.ui.theme.*
import com.fatalpuppet.volumex.provider.DriveFileProvider

@Composable
fun FilePreviewScreen(
    entry: FileSystemEntry,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val contentUri = remember(entry.inodeOid, entry.path) {
        DriveFileProvider.buildUri(entry.inodeOid, entry.path)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassWhite8)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(entry.formattedSize, color = TextTertiary, fontSize = 12.sp)
                }
            }

            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                when (entry.fileType) {
                    FileType.IMAGE -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(contentUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    FileType.VIDEO, FileType.AUDIO -> {
                        // Show a "open externally" placeholder for media
                        // Full MediaPlayer/ExoPlayer integration would add significant complexity
                        MediaUnsupportedPlaceholder(
                            icon = if (entry.fileType == FileType.VIDEO) Icons.Default.VideoFile else Icons.Default.AudioFile,
                            label = if (entry.fileType == FileType.VIDEO) "Video preview" else "Audio preview",
                            subLabel = "Tap 'Open With' to play in a media app"
                        )
                    }
                    else -> {
                        MediaUnsupportedPlaceholder(
                            icon = Icons.Default.InsertDriveFile,
                            label = entry.name,
                            subLabel = "No preview available for this file type"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaUnsupportedPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subLabel: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(GlassWhite8, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(subLabel, color = TextTertiary, fontSize = 13.sp)
    }
}
