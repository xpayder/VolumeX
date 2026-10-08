package com.fatalpuppet.volumex.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fatalpuppet.volumex.provider.DriveFileProvider
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileType
import com.fatalpuppet.volumex.ui.components.*
import com.fatalpuppet.volumex.ui.theme.*
import com.fatalpuppet.volumex.ui.viewmodel.BreadcrumbItem
import com.fatalpuppet.volumex.ui.viewmodel.FileBrowserViewModel
import com.fatalpuppet.volumex.ui.viewmodel.SortBy
import com.fatalpuppet.volumex.ui.viewmodel.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit = {},
    onOpenPreview: ((FileSystemEntry) -> Unit)? = null,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val entries by viewModel.entries.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val selectedEntries by viewModel.selectedEntries.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    // Context menu state
    var contextMenuEntry by remember { mutableStateOf<FileSystemEntry?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepNavy, DarkNavy, NavyMid)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with breadcrumbs
            FileBrowserTopBar(
                breadcrumbs = breadcrumbs,
                selectedCount = selectedEntries.size,
                statusMessage = statusMessage,
                viewMode = viewMode,
                showHidden = showHidden,
                onNavigateBack = {
                    if (searchActive) {
                        searchActive = false
                        viewModel.search("")
                    } else if (breadcrumbs.size > 1) {
                        viewModel.navigateUp()
                    } else {
                        onNavigateBack()
                    }
                },
                onCopySelected = { viewModel.copySelectedToAndroid(context) },
                onClearSelection = { viewModel.clearSelection() },
                onShowSortMenu = { showSortMenu = true },
                onToggleViewMode = { viewModel.toggleViewMode() },
                onToggleHidden = { viewModel.toggleHidden() },
                onSearchClick = { searchActive = !searchActive; if (!searchActive) viewModel.search("") },
                onSettingsClick = onOpenSettings,
                sortBy = sortBy
            )

            // Search bar
            AnimatedVisibility(visible = searchActive) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.search(it) },
                    onClose = { searchActive = false; viewModel.search("") }
                )
            }

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
                        onClick = { viewModel.setSortBy(sort); showSortMenu = false },
                        leadingIcon = {
                            if (sortBy == sort) Icon(Icons.Default.Check, null, tint = AccentBlue)
                        }
                    )
                }
            }

            // Transfer progress
            if (transferProgress.isNotEmpty()) {
                transferProgress.forEach { prog ->
                    TransferProgressCard(progress = prog, onCancel = { viewModel.clearTransferProgress() })
                }
                Spacer(Modifier.height(4.dp))
            }

            // Display: search results or directory listing
            val displayEntries = if (searchActive && searchQuery.isNotBlank()) searchResults else entries
            val isDisplayLoading = isLoading || (searchActive && isSearching)

            when {
                isDisplayLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (searchActive) "Searching…" else "Loading…",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                displayEntries.isEmpty() -> {
                    EmptyFolderView(path = if (searchActive) "No results for \"$searchQuery\"" else (breadcrumbs.lastOrNull()?.path ?: "/"))
                }
                viewMode == ViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayEntries, key = { it.path }) { entry ->
                            GridFileItem(
                                entry = entry,
                                isSelected = selectedEntries.contains(entry.path),
                                onClick = {
                                    if (selectedEntries.isNotEmpty()) {
                                        viewModel.toggleSelection(entry)
                                    } else {
                                        if (entry.isDirectory) viewModel.openEntry(entry)
                                        else onOpenPreview?.invoke(entry)
                                    }
                                },
                                onLongClick = { contextMenuEntry = entry }
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(displayEntries, key = { it.path }) { entry ->
                            FileListItem(
                                entry = entry,
                                isSelected = selectedEntries.contains(entry.path),
                                onClick = {
                                    if (selectedEntries.isNotEmpty()) {
                                        viewModel.toggleSelection(entry)
                                    } else {
                                        if (entry.isDirectory) {
                                            viewModel.openEntry(entry)
                                        } else {
                                            onOpenPreview?.invoke(entry)
                                        }
                                    }
                                },
                                onLongClick = { contextMenuEntry = entry }
                            )
                        }
                    }
                }
            }
        }

        // Context menu
        contextMenuEntry?.let { entry ->
            EntryContextMenu(
                entry = entry,
                onDismiss = { contextMenuEntry = null },
                onOpenWith = {
                    contextMenuEntry = null
                    val uri = DriveFileProvider.buildUri(entry.inodeOid, entry.path)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, getMimeType(entry))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(Intent.createChooser(intent, "Open with")) }
                    catch (e: Exception) { /* no app to handle */ }
                },
                onShare = {
                    contextMenuEntry = null
                    val uri = DriveFileProvider.buildUri(entry.inodeOid, entry.path)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = getMimeType(entry)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share"))
                },
                onCopyToPhone = {
                    contextMenuEntry = null
                    viewModel.toggleSelection(entry)
                    viewModel.copySelectedToAndroid(context)
                },
                onFolderDetails = if (entry.isDirectory) ({
                    contextMenuEntry = null
                    viewModel.getFolderDetails(entry)
                }) else null
            )
        }
    }
}

// ── Search Bar ────────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassWhite8)
            .border(1.dp, GlassBorderFaint, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search files…", color = TextTertiary, fontSize = 14.sp)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Grid Item ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileItem(
    entry: FileSystemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val bgColor = if (isSelected) GlassWhite16 else GlassWhite8
    val borderColor = if (isSelected) AccentBlue else GlassBorderFaint

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Thumbnail or icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GlassWhite8),
            contentAlignment = Alignment.Center
        ) {
            if (entry.fileType == FileType.IMAGE) {
                val uri = DriveFileProvider.buildUri(entry.inodeOid, entry.path)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = when (entry.fileType) {
                        FileType.DIRECTORY -> Icons.Default.Folder
                        FileType.VIDEO -> Icons.Default.VideoFile
                        FileType.AUDIO -> Icons.Default.AudioFile
                        FileType.DOCUMENT -> Icons.Default.Description
                        FileType.ARCHIVE -> Icons.Default.FolderZip
                        FileType.CODE -> Icons.Default.Code
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when (entry.fileType) {
                        FileType.DIRECTORY -> AccentBlue
                        FileType.VIDEO -> AccentOrange
                        FileType.AUDIO -> AccentGreen
                        else -> TextTertiary
                    },
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.name,
            color = TextPrimary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// ── Context Menu ──────────────────────────────────────────────────────────────

@Composable
private fun EntryContextMenu(
    entry: FileSystemEntry,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onCopyToPhone: () -> Unit,
    onFolderDetails: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(entry.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                if (!entry.isDirectory) {
                    ContextMenuItem(Icons.Default.OpenInNew, "Open with", onOpenWith)
                    ContextMenuItem(Icons.Default.Share, "Share", onShare)
                }
                ContextMenuItem(Icons.Default.Download, "Copy to phone", onCopyToPhone)
                if (onFolderDetails != null) {
                    ContextMenuItem(Icons.Default.Info, "Folder details", onFolderDetails)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextTertiary) }
        },
        containerColor = DarkCard,
        titleContentColor = TextPrimary
    )
}

@Composable
private fun ContextMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp)
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@Composable
private fun FileBrowserTopBar(
    breadcrumbs: List<BreadcrumbItem>,
    selectedCount: Int,
    statusMessage: String,
    viewMode: ViewMode,
    showHidden: Boolean,
    onNavigateBack: () -> Unit,
    onCopySelected: () -> Unit,
    onClearSelection: () -> Unit,
    onShowSortMenu: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleHidden: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    sortBy: SortBy
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassBackground(shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), borderWidth = 0.dp)
    ) {
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
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, "Search", tint = TextSecondary)
                }
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        "Toggle hidden",
                        tint = if (showHidden) AccentBlue else TextSecondary
                    )
                }
                IconButton(onClick = onToggleViewMode) {
                    Icon(
                        if (viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                        "Toggle view mode",
                        tint = TextSecondary
                    )
                }
                IconButton(onClick = onShowSortMenu) {
                    Icon(Icons.Default.Sort, "Sort", tint = TextSecondary)
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(breadcrumbs) { crumb ->
                val isLast = crumb == breadcrumbs.last()
                BreadcrumbChip(crumb, isLast = isLast)
                if (!isLast) {
                    Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
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
            .border(1.dp, if (isLast) AccentBlue.copy(alpha = 0.5f) else GlassBorderFaint, RoundedCornerShape(6.dp))
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

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun getMimeType(entry: FileSystemEntry): String {
    val ext = entry.extension
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
