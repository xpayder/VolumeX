package app.feldkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.*
import app.feldkit.ui.viewmodel.FileBrowserViewModel.OffloadUi

/** Progress of a save job: what is being copied, the two phases (copy, read-back check), speed, and the verdict when it ends. */
@Composable
fun OffloadCard(ui: OffloadUi, onCancel: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val places = ui.destLabels.size
    val problem = ui.problems.isNotEmpty()
    GlassCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), elevated = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    ui.finished && problem -> Icon(Icons.Default.ErrorOutline, null, tint = AccentOrange, modifier = Modifier.size(24.dp))
                    ui.finished -> Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                    else -> Unit
                }
                if (ui.finished) Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ui.cancelled -> "Cancelled"
                            ui.finished && problem -> "${ui.problems.size} problem${if (ui.problems.size != 1) "s" else ""}, ${ui.verifiedFiles} of ${ui.fileCount} files fine"
                            ui.finished -> (if (ui.verifying) "Verified: " else "Saved: ") + "${ui.fileCount} file${if (ui.fileCount != 1) "s" else ""} in $places place${if (places != 1) "s" else ""}"
                            else -> "${ui.phase} ${ui.fileIndex} of ${ui.fileCount}"
                        },
                        color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (!ui.finished) Text(ui.file, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(ui.destLabels.joinToString("  ·  "), color = TextTertiary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = if (ui.finished) onClose else onCancel) { Icon(Icons.Default.Close, if (ui.finished) "Close" else "Cancel", tint = TextTertiary) }
            }
            if (!ui.finished) {
                val frac = if (ui.totalBytes > 0) (ui.doneBytes.toFloat() / ui.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, color = Accent, trackColor = Fill2, modifier = Modifier.fillMaxWidth().height(6.dp))
                if (ui.verifying) {
                    val vf = if (ui.totalBytes > 0) (ui.verifiedBytes.toFloat() / ui.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { vf }, color = AccentGreen, trackColor = Fill1, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
                Text(
                    "${FileSystemEntry.formatBytes(ui.doneBytes)} of ${FileSystemEntry.formatBytes(ui.totalBytes)}  ·  ${FileSystemEntry.formatBytes(ui.speedBps)}/s" + (if (ui.verifying) "  ·  checked ${FileSystemEntry.formatBytes(ui.verifiedBytes)}" else ""),
                    color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            } else if (problem) {
                ui.problems.take(4).forEach { Text(it, color = AccentOrange, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (ui.problems.size > 4) Text("and ${ui.problems.size - 4} more", color = TextTertiary, fontSize = 12.sp)
            }
        }
    }
}
