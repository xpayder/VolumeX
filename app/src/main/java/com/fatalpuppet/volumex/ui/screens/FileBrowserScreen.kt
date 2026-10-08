package com.fatalpuppet.volumex.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.ui.components.*
import com.fatalpuppet.volumex.ui.theme.*
import com.fatalpuppet.volumex.ui.viewmodel.BreadcrumbItem
import com.fatalpuppet.volumex.ui.viewmodel.FileBrowserViewModel
import com.fatalpuppet.volumex.ui.viewmodel.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val entries by viewModel.entries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val selectedEntries by viewModel.selectedEntries.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(DeepNavy, DarkNavy, NavyMid))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with breadcrumbs
            FileBrowserTopBar(
                breadcrumbs = breadcrumbs,
                selectedCount = selectedEntries.size,
                statusMessage = statusMessage,
                onNavigateBack = {
                    if (breadcrumbs.size > 1) viewModel.navigateUp() else onNavigateBack()
                },
                onCopySelected = { viewModel.copySelectedToAndroid(context) },
                onClearSelection = { viewModel.clearSelection() },
                onShowSortMenu = { showSortMenu = true },
                sortBy = sortBy
            )

            // Sort menu
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
                modifier = Modifier.background(DarkCard)
            ) {
                SortBy.values().forEach { sort ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sort.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = if (sortBy == sort) AccentBlue else TextPrimary
                            )
                        },
                        onClick = {
                            viewModel.setSortBy(sort)
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (sortBy == sort) Icon(Icons.Default.Check, null, tint = AccentBlue)
                        }
                    )
                }
            }

            // Transfer progress
            if (transferProgress.isNotEmpty()) {
                transferProgress.forEach { prog ->
                    TransferProgressCard(
                        progress = prog,
                        onCancel = { viewModel.clearTransferProgress() }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // File list
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading...", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
                entries.isEmpty() -> {
                    EmptyFolderView(
                        path = breadcrumbs.lastOrNull()?.path ?: "/"
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(entries, key = { it.path }) { entry ->
                            FileListItem(
                                entry = entry,
                                isSelected = selectedEntries.contains(entry.path),
                                onClick = {
                                    if (selectedEntries.isNotEmpty()) {
                                        viewModel.toggleSelection(entry)
                                    } else {
                                        viewModel.openEntry(entry)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserTopBar(
    breadcrumbs: List<BreadcrumbItem>,
    selectedCount: Int,
    statusMessage: String,
    onNavigateBack: () -> Unit,
    onCopySelected: () -> Unit,
    onClearSelection: () -> Unit,
    onShowSortMenu: () -> Unit,
    sortBy: SortBy
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassBackground(
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                borderWidth = 0.dp
            )
    ) {
        // Action bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = if (selectedCount > 0) "$selectedCount selected" else statusMessage,
                color = if (selectedCount > 0) AccentBlue else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )

            if (selectedCount > 0) {
                IconButton(onClick = onCopySelected) {
                    Icon(Icons.Default.Download, "Copy to Android", tint = AccentBlue)
                }
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, "Clear", tint = TextSecondary)
                }
            } else {
                IconButton(onClick = onShowSortMenu) {
                    Icon(Icons.Default.Sort, "Sort", tint = TextSecondary)
                }
            }
        }

        // Breadcrumbs
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(breadcrumbs) { crumb ->
                val isLast = crumb == breadcrumbs.last()
                BreadcrumbChip(crumb, isLast = isLast)
                if (!isLast) {
                    Icon(
                        Icons.Default.ChevronRight,
                        null,
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(crumb: BreadcrumbItem, isLast: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isLast) AccentBlue.copy(alpha = 0.2f) else GlassWhite8)
            .border(
                1.dp,
                if (isLast) AccentBlue.copy(alpha = 0.5f) else GlassBorderFaint,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = crumb.name,
            color = if (isLast) AccentBlue else TextTertiary,
            fontSize = 12.sp,
            fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
