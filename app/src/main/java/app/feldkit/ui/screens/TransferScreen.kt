package app.feldkit.ui.screens

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
import app.feldkit.storage.TransferProgress
import app.feldkit.ui.components.*
import app.feldkit.ui.theme.*
import app.feldkit.ui.viewmodel.TransferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    viewModel: TransferViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val queue by viewModel.queue.collectAsState()
    val isTransferring by viewModel.isTransferring.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            app.feldkit.ui.components.GlassTopBar(
                title = "Transfers",
                subtitle = if (isTransferring) "${queue.count { !it.isComplete && it.error == null }} active" else "${queue.size} items",
                onBack = onNavigateBack,
                actions = {
                    if (isTransferring) {
                        LiquidButton(label = "Cancel All", onClick = { viewModel.cancelAll() }, modifier = Modifier.height(36.dp))
                    } else if (queue.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQueue() }) { Icon(Icons.Default.ClearAll, "Clear", tint = TextSecondary) }
                    }
                }
            )

            // Stats summary
            if (queue.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TransferSummaryRow(queue)
            }

            // Transfer queue
            when {
                queue.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Default.CloudDownload,
                        title = "No Transfers",
                        subtitle = "Select files in the file browser and tap the download button to copy them to your device"
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(queue, key = { it.fileName + it.totalBytes }) { progress ->
                            TransferProgressCard(
                                progress = progress,
                                onCancel = { viewModel.cancelAll() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferSummaryRow(queue: List<TransferProgress>) {
    val completed = queue.count { it.isComplete && it.error == null }
    val failed = queue.count { it.error != null }
    val active = queue.count { !it.isComplete && it.error == null }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryChip(label = "Active", count = active, color = Accent, modifier = Modifier.weight(1f))
        SummaryChip(label = "Done", count = completed, color = AccentGreen, modifier = Modifier.weight(1f))
        if (failed > 0) {
            SummaryChip(label = "Failed", count = failed, color = AccentRed, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SummaryChip(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$count", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = label, color = color.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}
