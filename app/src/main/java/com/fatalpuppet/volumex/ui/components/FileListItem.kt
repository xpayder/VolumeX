package com.fatalpuppet.volumex.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    entry: FileSystemEntry,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) GlassWhite16 else Color.Transparent,
        label = "bgColor"
    )
    val borderColor = if (isSelected) AccentBlue else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File icon
        FileIconBox(entry)

        Spacer(Modifier.width(12.dp))

        // Name + metadata
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val dateText = formatDate(entry.modifiedAt)
            val meta = listOfNotNull(
                if (entry.isDirectory) "Folder" else entry.formattedSize,
                dateText.ifEmpty { null }
            ).joinToString(" · ")
            Text(text = meta, color = TextTertiary, fontSize = 12.sp)
        }

        // Chevron for directories
        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun FileIconBox(entry: FileSystemEntry) {
    val (icon, tint) = getFileIconAndColor(entry)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.25f), tint.copy(alpha = 0.12f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun getFileIconAndColor(entry: FileSystemEntry): Pair<ImageVector, Color> {
    if (entry.isDirectory) return Pair(Icons.Default.Folder, AccentBlue)
    return when (entry.extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif" ->
            Pair(Icons.Default.Image, AccentPurple)
        "mp4", "mov", "avi", "mkv", "m4v" ->
            Pair(Icons.Default.VideoFile, AccentOrange)
        "mp3", "aac", "flac", "wav", "m4a" ->
            Pair(Icons.Default.AudioFile, AccentGreen)
        "pdf" -> Pair(Icons.Default.PictureAsPdf, AccentRed)
        "zip", "gz", "tar", "7z", "rar" ->
            Pair(Icons.Default.FolderZip, AccentOrange)
        "txt", "md", "log" ->
            Pair(Icons.Default.Description, TextSecondary)
        "app", "dmg", "pkg" ->
            Pair(Icons.Default.Apps, AccentPurple)
        else -> Pair(Icons.Default.InsertDriveFile, TextTertiary)
    }
}

private fun formatDate(ms: Long): String {
    if (ms <= 0) return ""
    return try {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        sdf.format(Date(ms))
    } catch (e: Exception) {
        ""
    }
}
