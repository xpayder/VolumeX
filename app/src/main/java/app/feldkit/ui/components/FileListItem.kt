package app.feldkit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import app.feldkit.ui.screens.AudioPlayButton
import app.feldkit.ui.screens.AudioSeekLine
import app.feldkit.ui.screens.AudioTimeText
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
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileType
import app.feldkit.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    entry: FileSystemEntry,
    isSelected: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    /** Tap on the icon tile: toggles the selection (the row itself opens the item). */
    onIconClick: (() -> Unit)? = null,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) GlassWhite16 else if (highlight) Accent.copy(alpha = 0.09f) else Color.Transparent,
        label = "bgColor"
    )
    val borderColor = if (isSelected) Accent else if (highlight) Accent.copy(alpha = 0.28f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = if (highlight) 8.dp else 16.dp, top = 12.dp, bottom = if (highlight) 2.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (highlight) AudioPlayButton() else FileIconBox(entry, isSelected, onIconClick)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = if (highlight) Accent else TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                if (highlight) AudioTimeText() else {
                    val dateText = formatDate(entry.modifiedAt)
                    val meta = listOfNotNull(
                        if (entry.isDirectory) "Folder" else entry.formattedSize,
                        dateText.ifEmpty { null }
                    ).joinToString(" · ")
                    Text(text = meta, color = TextTertiary, fontSize = 12.sp)
                }
            }

            if (entry.isDirectory && !highlight) Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
        if (highlight) AudioSeekLine(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
    }
}

@Composable
private fun FileIconBox(entry: FileSystemEntry, selected: Boolean, onClick: (() -> Unit)?) {
    val (icon, tint) = getFileIconAndColor(entry)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brush.verticalGradient(listOf(Accent, Accent)) else Brush.verticalGradient(listOf(tint.copy(alpha = 0.25f), tint.copy(alpha = 0.12f))))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Default.Check, "Selected", tint = DeepNavy, modifier = Modifier.size(24.dp))
        else Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

internal fun getFileIconAndColor(entry: FileSystemEntry): Pair<ImageVector, Color> = when (entry.fileType) {
    FileType.DIRECTORY -> Pair(Icons.Default.Folder, Accent)
    FileType.IMAGE -> Pair(Icons.Default.Image, AccentLight)
    FileType.VIDEO -> Pair(Icons.Default.VideoFile, AccentAmber)
    FileType.AUDIO -> Pair(Icons.Default.AudioFile, AccentGreen)
    FileType.PDF -> Pair(Icons.Default.PictureAsPdf, AccentRed)
    FileType.ARCHIVE -> Pair(if (entry.extension in setOf("dmg", "pkg", "app")) Icons.Default.Apps else Icons.Default.FolderZip, AccentSand)
    FileType.TEXT, FileType.DOCUMENT -> Pair(Icons.Default.Description, TextSecondary)
    FileType.CODE -> Pair(Icons.Default.Code, Accent)
    else -> Pair(Icons.Default.InsertDriveFile, TextTertiary)
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
