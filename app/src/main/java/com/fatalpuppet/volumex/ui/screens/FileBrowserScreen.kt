package com.fatalpuppet.volumex.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fatalpuppet.volumex.provider.DriveFileProvider
import com.fatalpuppet.volumex.storage.ActiveDriveSession
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileType
import com.fatalpuppet.volumex.ui.components.*
import com.fatalpuppet.volumex.ui.theme.*
import com.fatalpuppet.volumex.ui.viewmodel.FileBrowserViewModel
import com.fatalpuppet.volumex.ui.viewmodel.SortBy
import com.fatalpuppet.volumex.ui.viewmodel.ViewMode
import java.text.DateFormat
import java.util.Date

private val Mono = FontFamily.Monospace

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
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val selected by viewModel.selectedEntries.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val writable by viewModel.writable.collectAsState()
    val message by viewModel.message.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()

    var searchActive by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<FileSystemEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileSystemEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var propertiesOf by remember { mutableStateOf<FileSystemEntry?>(null) }
    var pendingCopy by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var askDest by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val driveName = remember { ActiveDriveSession.volumes.getOrNull(ActiveDriveSession.currentVolumeIndex)?.name?.takeIf { it.isNotBlank() } ?: "Drive" }
    val title = if (breadcrumbs.size > 1) breadcrumbs.last().name else driveName
    val visible = entries.filter { showHidden || !it.name.startsWith(".") }

    LaunchedEffect(message) { message?.let { snackbarHost.showSnackbar(it); viewModel.consumeMessage() } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.importUris(context, uris) }
    val uploadTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.importTree(context, uri) }
    val saveTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pendingCopy.isNotEmpty()) viewModel.copyToTree(context, uri, pendingCopy)
        pendingCopy = emptyList()
    }
    fun askDestination(items: List<FileSystemEntry>) { if (items.isNotEmpty()) { pendingCopy = items; askDest = true } }

    fun open(entry: FileSystemEntry) { if (entry.isDirectory) viewModel.openEntry(entry) else onOpenPreview?.invoke(entry) }
    fun back() {
        when {
            selected.isNotEmpty() -> viewModel.clearSelection()
            searchActive -> { searchActive = false; viewModel.search("") }
            breadcrumbs.size > 1 -> viewModel.navigateUp()
            else -> onNavigateBack()
        }
    }

    val hazeState = rememberHazeState()
    var headerPx by remember { mutableIntStateOf(0) }
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }

    Box(Modifier.fillMaxSize().background(DeepNavy)) {
        Column(Modifier.align(Alignment.TopCenter).zIndex(1f).onSizeChanged { headerPx = it.height }) {
            // ── header card ──────────────────────────────────────────────────
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .liquidGlass(hazeState, RoundedCornerShape(24.dp))
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
                    Text(
                        if (selected.isNotEmpty()) "${selected.size} selected" else title,
                        color = if (selected.isNotEmpty()) AccentBlue else TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { askDestination(viewModel.entriesByPaths(selected)) }) { Icon(Icons.Default.Download, "Save to phone", tint = TextSecondary) }
                        if (writable) {
                            IconButton(onClick = { viewModel.setClipboard(viewModel.entriesByPaths(selected), false); viewModel.clearSelection() }) { Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary) }
                            IconButton(onClick = { deleteTargets = viewModel.entriesByPaths(selected) }) { Icon(Icons.Default.Delete, "Delete", tint = AccentRed) }
                        }
                    } else {
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(if (viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView, "View", tint = TextSecondary)
                        }
                        IconButton(onClick = { searchActive = !searchActive; if (!searchActive) viewModel.search("") }) { Icon(Icons.Default.Search, "Search", tint = TextSecondary) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(DarkCard)) {
                                SortBy.values().forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text("Sort by ${sort.name.lowercase()}", color = if (sortBy == sort) AccentBlue else TextPrimary) },
                                        leadingIcon = { if (sortBy == sort) Icon(Icons.Default.Check, null, tint = AccentBlue) else Spacer(Modifier.size(24.dp)) },
                                        onClick = { viewModel.setSortBy(sort); menuOpen = false }
                                    )
                                }
                                HorizontalDivider(color = GlassBorderFaint)
                                DropdownMenuItem(
                                    text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files", color = TextPrimary) },
                                    leadingIcon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSecondary) },
                                    onClick = { viewModel.toggleHidden(); menuOpen = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Settings, null, tint = TextSecondary) },
                                    onClick = { menuOpen = false; onOpenSettings() }
                                )
                            }
                        }
                    }
                }
                // breadcrumb path + count
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(breadcrumbs.size) { i ->
                            val c = breadcrumbs[i]; val last = i == breadcrumbs.lastIndex
                            Text(
                                if (i == 0) driveName else c.name, color = if (last) TextSecondary else AccentBlue, fontSize = 12.sp, fontFamily = Mono,
                                maxLines = 1, modifier = Modifier.clickable(enabled = !last) { viewModel.navigateTo(c.path) }
                            )
                            if (!last) Text("›", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
                        }
                    }
                    Text("${visible.size} item${if (visible.size != 1) "s" else ""}${if (!writable) " · read-only" else ""}", color = TextTertiary, fontSize = 11.sp, fontFamily = Mono)
                }
            }

            AnimatedVisibility(visible = searchActive) {
                BrowserSearchField(searchQuery, { viewModel.search(it) }, { searchActive = false; viewModel.search("") })
            }

            if (transferProgress.isNotEmpty()) {
                transferProgress.takeLast(3).forEach { TransferProgressCard(progress = it, onCancel = { viewModel.clearTransferProgress() }) }
            }

        }
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            val display = if (searchActive && searchQuery.isNotBlank()) searchResults else visible
            when {
                isLoading || (searchActive && isSearching) -> Box(Modifier.fillMaxSize().padding(top = headerDp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentBlue) }
                display.isEmpty() -> EmptyFolderView(path = if (searchActive) "No results for \"$searchQuery\"" else (breadcrumbs.lastOrNull()?.path ?: "/"))
                viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerDp + 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()
                ) {
                    items(display, key = { it.path }) { e ->
                        GridCell(e, selected.contains(e.path), { if (selected.isNotEmpty()) viewModel.toggleSelection(e) else open(e) }, { viewModel.toggleSelection(e) }, { sheetEntry = e })
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerDp + 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(display, key = { it.path }) { e ->
                        FileListItem(entry = e, isSelected = selected.contains(e.path), onClick = { if (selected.isNotEmpty()) viewModel.toggleSelection(e) else open(e) }, onLongClick = { sheetEntry = e })
                    }
                }
            }
        }

        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 16.dp, end = 16.dp))

        // ── floating action pill ─────────────────────────────────────────────
        if (writable && selected.isEmpty() && !searchActive) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).liquidGlass(hazeState, RoundedCornerShape(28.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (clipboard != null) {
                    PillAction(Icons.Default.ContentPaste, "Paste (${clipboard!!.entries.size})") { viewModel.pasteHere() }
                    PillAction(Icons.Default.Close, "Cancel") { viewModel.clearClipboard() }
                } else {
                    PillAction(Icons.Default.CreateNewFolder, "New folder") { showNewFolder = true }
                    PillAction(Icons.Default.UploadFile, "Upload files") { importLauncher.launch(arrayOf("*/*")) }
                    PillAction(Icons.Default.DriveFolderUpload, "Upload folder") { uploadTreeLauncher.launch(null) }
                }
            }
        }

        // ── dialogs ──────────────────────────────────────────────────────────
        if (askDest) {
            AlertDialog(
                onDismissRequest = { askDest = false; pendingCopy = emptyList() },
                title = { Text("Save to phone", color = TextPrimary) },
                text = {
                    Column {
                        SheetRow(Icons.Default.Download, "Downloads / VolumeX") { askDest = false; viewModel.copyToDownloads(context, pendingCopy); pendingCopy = emptyList() }
                        SheetRow(Icons.Default.FolderOpen, "Choose another folder…") { askDest = false; saveTreeLauncher.launch(null) }
                        Text("Android's folder picker cannot pick Downloads or the storage root, so use the first option for those.", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {}, dismissButton = { TextButton(onClick = { askDest = false; pendingCopy = emptyList() }) { Text("Cancel", color = TextTertiary) } }, containerColor = DarkCard, shape = RoundedCornerShape(28.dp)
            )
        }
        if (showNewFolder) TextInputDialog("New folder", "", "Create", { showNewFolder = false }) { viewModel.createFolder(it); showNewFolder = false }
        renameTarget?.let { t -> TextInputDialog("Rename", t.name, "Rename", { renameTarget = null }) { viewModel.renameEntry(t, it); renameTarget = null } }
        if (deleteTargets.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { deleteTargets = emptyList() },
                title = { Text("Delete ${deleteTargets.size} item${if (deleteTargets.size != 1) "s" else ""}?", color = TextPrimary) },
                text = { Text("This permanently removes ${if (deleteTargets.size == 1) "\"${deleteTargets[0].name}\"" else "the selected items"} from the drive. It cannot be undone.", color = TextSecondary) },
                confirmButton = { TextButton(onClick = { viewModel.deleteEntries(deleteTargets); deleteTargets = emptyList() }) { Text("Delete", color = AccentRed) } },
                dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel", color = TextTertiary) } }, containerColor = DarkCard, shape = RoundedCornerShape(28.dp)
            )
        }
        propertiesOf?.let { e -> PropertiesDialog(e, driveName) { propertiesOf = null } }

        // ── bottom sheet with the file actions ───────────────────────────────
        sheetEntry?.let { e ->
            ModalBottomSheet(onDismissRequest = { sheetEntry = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = DarkSurface, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), contentColor = TextPrimary) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(DarkCard), contentAlignment = Alignment.Center) {
                            Icon(if (e.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = AccentBlue)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (e.isDirectory) "Folder" else e.formattedSize, color = TextTertiary, fontSize = 12.sp, fontFamily = Mono)
                        }
                    }
                    HorizontalDivider(color = GlassBorderFaint, modifier = Modifier.padding(vertical = 4.dp))
                    SheetRow(Icons.Default.OpenInNew, if (e.isDirectory) "Open" else "Open") { sheetEntry = null; open(e) }
                    if (!e.isDirectory) {
                        SheetRow(Icons.Default.Apps, "Open with…") {
                            sheetEntry = null
                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(DriveFileProvider.buildUri(e.inodeOid, e.path), mimeOf(e)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            try { context.startActivity(Intent.createChooser(intent, "Open with")) } catch (_: Exception) {}
                        }
                        SheetRow(Icons.Default.Share, "Share") {
                            sheetEntry = null
                            val intent = Intent(Intent.ACTION_SEND).apply { type = mimeOf(e); putExtra(Intent.EXTRA_STREAM, DriveFileProvider.buildUri(e.inodeOid, e.path)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            context.startActivity(Intent.createChooser(intent, "Share"))
                        }
                    }
                    SheetRow(Icons.Default.Download, "Save to…") { sheetEntry = null; askDestination(listOf(e)) }
                    SheetRow(Icons.Default.Info, "Properties") { sheetEntry = null; propertiesOf = e }
                    if (writable) {
                        HorizontalDivider(color = GlassBorderFaint, modifier = Modifier.padding(vertical = 4.dp))
                        SheetRow(Icons.Default.ContentCopy, "Copy") { sheetEntry = null; viewModel.setClipboard(listOf(e), false) }
                        SheetRow(Icons.Default.ContentCut, "Move") { sheetEntry = null; viewModel.setClipboard(listOf(e), true) }
                        SheetRow(Icons.Default.Edit, "Rename") { sheetEntry = null; renameTarget = e }
                        SheetRow(Icons.Default.Delete, "Delete", AccentRed) { sheetEntry = null; deleteTargets = listOf(e) }
                    }
                }
            }
        }
    }
}

// ── pieces ───────────────────────────────────────────────────────────────────

@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.92f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    Column(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(20.dp)).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = TextPrimary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (tint == TextPrimary) TextSecondary else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = tint, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(entry: FileSystemEntry, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(DarkCard)
                .border(if (isSelected) 2.dp else 1.dp, if (isSelected) AccentBlue else GlassBorderFaint, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.fileType == FileType.VIDEO) {
                VideoThumb(entry, Modifier.fillMaxSize())
            } else if (entry.fileType == FileType.IMAGE) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(DriveFileProvider.buildUri(entry.inodeOid, entry.path)).size(360).crossfade(true).build(),
                    contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    when (entry.fileType) {
                        FileType.DIRECTORY -> Icons.Default.Folder; FileType.VIDEO -> Icons.Default.PlayCircle; FileType.AUDIO -> Icons.Default.AudioFile
                        FileType.DOCUMENT -> Icons.Default.Description; FileType.ARCHIVE -> Icons.Default.FolderZip; FileType.CODE -> Icons.Default.Code
                        else -> Icons.Default.InsertDriveFile
                    },
                    null, modifier = Modifier.size(40.dp),
                    tint = when (entry.fileType) { FileType.DIRECTORY -> AccentBlue; FileType.VIDEO -> AccentOrange; FileType.AUDIO -> AccentGreen; else -> TextTertiary }
                )
            }
            if (isSelected) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape).background(AccentBlue), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = DeepNavy, modifier = Modifier.size(16.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(DeepNavy.copy(alpha = 0.6f)).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.MoreVert, "Actions", tint = TextPrimary, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(entry.name, color = TextPrimary, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (entry.isDirectory) "Folder" else entry.formattedSize, color = TextTertiary, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = Mono)
    }
}

@Composable
private fun BrowserSearchField(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(DarkSurface)
            .border(1.dp, GlassBorderFaint, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = onChange, modifier = Modifier.weight(1f), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentBlue),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search this drive", color = TextTertiary, fontSize = 15.sp); inner() } }
        )
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close search", tint = TextTertiary, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun PropertiesDialog(e: FileSystemEntry, drive: String, onDismiss: () -> Unit) {
    fun date(ms: Long) = if (ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropRow("Name", e.name); PropRow("Where", "$drive${e.path.substringBeforeLast('/', "")}/")
                PropRow("Kind", if (e.isDirectory) "Folder" else (e.extension.uppercase().ifEmpty { "File" }))
                if (!e.isDirectory) PropRow("Size", "${e.formattedSize}  (${e.size} bytes)")
                PropRow("Modified", date(e.modifiedAt)); PropRow("Created", date(e.createdAt))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = AccentBlue) } }, containerColor = DarkCard, shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun PropRow(k: String, v: String) {
    Column { Text(k.uppercase(), color = TextTertiary, fontSize = 10.sp, fontFamily = Mono); Text(v, color = TextPrimary, fontSize = 14.sp) }
}

@Composable
private fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title, color = TextPrimary) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(confirm, color = AccentBlue) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextTertiary) } }, containerColor = DarkCard, shape = RoundedCornerShape(28.dp)
    )
}

private fun mimeOf(e: FileSystemEntry): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension) ?: "*/*"
