package app.feldkit.ui.screens

import kotlinx.coroutines.launch
import app.feldkit.ui.components.OffloadCard
import androidx.compose.ui.focus.focusRequester
import app.feldkit.ui.components.getFileIconAndColor
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import app.feldkit.ui.components.GlassEase
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import app.feldkit.provider.DriveFileProvider
import app.feldkit.storage.ActiveDriveSession
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileType
import app.feldkit.ui.components.*
import app.feldkit.ui.theme.*
import app.feldkit.ui.viewmodel.FileBrowserViewModel
import app.feldkit.ui.viewmodel.SortBy
import app.feldkit.ui.viewmodel.ViewMode
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
    val offload by viewModel.offload.collectAsState()
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
    var checksumOf by remember { mutableStateOf<FileSystemEntry?>(null) }
    var infoOf by remember { mutableStateOf<FileSystemEntry?>(null) }
    var pendingCopy by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var askDest by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val imageLabel by viewModel.imageLabel.collectAsState()
    val driveBase = remember { ActiveDriveSession.volumes.getOrNull(ActiveDriveSession.currentVolumeIndex)?.name?.takeIf { it.isNotBlank() } ?: "Drive" }
    val driveName = imageLabel ?: driveBase
    val title = if (breadcrumbs.size > 1) breadcrumbs.last().name else driveName
    val visible = entries.filter { showHidden || !it.name.startsWith(".") }

    LaunchedEffect(Unit) { viewModel.attach(context) }
    LaunchedEffect(message) { message?.let { snackbarHost.showSnackbar(it); viewModel.consumeMessage() } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.importUris(context, uris) }
    val uploadTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.importTree(context, uri) }
    val saveTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pendingCopy.isNotEmpty()) viewModel.copyToTree(context, uri, pendingCopy)
        pendingCopy = emptyList()
    }
    fun askDestination(items: List<FileSystemEntry>) { if (items.isNotEmpty()) { pendingCopy = items; askDest = true } }

    val imageScope = rememberCoroutineScope()
    fun open(entry: FileSystemEntry) {
        when {
            entry.isDirectory -> viewModel.openEntry(entry)
            entry.extension in ImageExt -> imageScope.launch { viewModel.openImage(entry)?.let { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show(); onOpenPreview?.invoke(entry) } }
            entry.fileType == FileType.AUDIO -> AudioSession.play(context, entry, visible.filter { it.fileType == FileType.AUDIO && !it.isDirectory })
            else -> onOpenPreview?.invoke(entry)
        }
    }
    fun back() {
        when {
            selected.isNotEmpty() -> viewModel.clearSelection()
            searchActive -> { searchActive = false; viewModel.search("") }
            breadcrumbs.size > 1 -> viewModel.navigateUp()
            viewModel.inImage -> viewModel.leaveImage()
            else -> onNavigateBack()
        }
    }

    val hazeState = LocalHaze.current ?: rememberHazeState()
    var headerPx by remember { mutableIntStateOf(0) }
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }

    Box(Modifier.fillMaxSize()) {
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
                        color = if (selected.isNotEmpty()) Accent else TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { viewModel.selectAll(visible) }) { Icon(Icons.Default.SelectAll, "Select all", tint = TextSecondary) }
                    } else {
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(if (viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView, "View", tint = TextSecondary)
                        }
                        IconButton(onClick = { searchActive = !searchActive; if (!searchActive) viewModel.search("") }) { Icon(Icons.Default.Search, "Search", tint = TextSecondary) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                            if (menuOpen) GlassMenu(onDismissRequest = { menuOpen = false }) {
                                SortBy.values().forEach { sort ->
                                    GlassMenuItem(
                                        "Sort by ${sort.name.lowercase()}", tint = if (sortBy == sort) Accent else TextPrimary,
                                        icon = { if (sortBy == sort) Icon(Icons.Default.Check, null, tint = Accent) else Spacer(Modifier.size(24.dp)) }
                                    ) { viewModel.setSortBy(sort); menuOpen = false }
                                }
                                HorizontalDivider(color = GlassBorderFaint, modifier = Modifier.padding(vertical = 4.dp))
                                GlassMenuItem(
                                    if (showHidden) "Hide hidden files" else "Show hidden files",
                                    icon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSecondary) }
                                ) { viewModel.toggleHidden(); menuOpen = false }
                                GlassMenuItem("Settings", icon = { Icon(Icons.Default.Settings, null, tint = TextSecondary) }) { menuOpen = false; onOpenSettings() }
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
                                if (i == 0) driveName else c.name, color = if (last) TextSecondary else Accent, fontSize = 12.sp, fontFamily = Mono,
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

            offload?.let { OffloadCard(it, onCancel = { viewModel.cancelOffload() }, onClose = { viewModel.dismissOffload() }) }
            if (transferProgress.isNotEmpty()) {
                transferProgress.takeLast(3).forEach { TransferProgressCard(progress = it, onCancel = { viewModel.clearTransferProgress() }) }
            }

        }
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            val display = if (searchActive && searchQuery.isNotBlank()) searchResults else visible
            // The previous folder stays on screen until the next one is ready, then they cross-fade: no blank frame, no spinner flash.
            AnimatedContent(
                targetState = display,
                transitionSpec = { fadeIn(tween(240, delayMillis = 70, easing = GlassEase)) togetherWith fadeOut(tween(110)) },
                label = "folder"
            ) { items ->
                when {
                    items.isEmpty() && (isLoading || (searchActive && isSearching)) -> Box(Modifier.fillMaxSize())
                    items.isEmpty() -> EmptyFolderView(path = if (searchActive) "No results for \"$searchQuery\"" else (breadcrumbs.lastOrNull()?.path ?: "/"))
                    viewMode == ViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerDp + 4.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()
                    ) {
                        items(items, key = { it.path }) { e ->
                            GridCell(e, selected.contains(e.path), AudioSession.currentPath == e.path, { if (selected.isNotEmpty()) viewModel.toggleSelection(e) else open(e) }, { viewModel.toggleSelection(e) }, { sheetEntry = e })
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerDp + 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(items, key = { it.path }) { e ->
                            FileListItem(entry = e, isSelected = selected.contains(e.path), highlight = AudioSession.currentPath == e.path, onClick = { if (selected.isNotEmpty()) viewModel.toggleSelection(e) else open(e) }, onLongClick = { sheetEntry = e }, onIconClick = { viewModel.toggleSelection(e) })
                        }
                    }
                }
            }
            // Only a slow load earns a spinner, and it floats instead of replacing the list.
            val slow by produceState(false, isLoading) { if (isLoading) { delay(350); value = true } else value = false }
            if (slow) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
        }

        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 16.dp, end = 16.dp)) { data ->
            Text(
                data.visuals.message, color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.glassOrSolid(RoundedCornerShape(20.dp), GlassLevel.Sheet).padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        // ── contextual action bar: what you can do depends on what is selected ──────────────────────
        val barMode = when {
            selected.isNotEmpty() -> 1
            clipboard != null && writable -> 2
            writable && !searchActive -> 3
            else -> 0
        }
        AnimatedVisibility(
            visible = barMode != 0, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }
        ) {
            AnimatedContent(targetState = barMode.coerceAtLeast(1), transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) }, label = "actionBar") { mode ->
                Row(
                    Modifier.liquidGlass(hazeState, RoundedCornerShape(28.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (mode) {
                        1 -> {
                            val picked = viewModel.entriesByPaths(selected)
                            PillAction(Icons.Default.Download, "Save") { askDestination(picked) }
                            if (writable) {
                                PillAction(Icons.Default.ContentCopy, "Copy") { viewModel.setClipboard(picked, false); viewModel.clearSelection() }
                                PillAction(Icons.Default.ContentCut, "Move") { viewModel.setClipboard(picked, true); viewModel.clearSelection() }
                            }
                            PillAction(Icons.Default.Share, "Share") { shareMany(context, picked) }
                            if (writable) PillAction(Icons.Default.Delete, "Delete", tint = AccentRed) { deleteTargets = picked }
                        }
                        2 -> {
                            PillAction(Icons.Default.ContentPaste, "Paste (${clipboard?.entries?.size ?: 0})") { viewModel.pasteHere() }
                            PillAction(Icons.Default.Close, "Cancel") { viewModel.clearClipboard() }
                        }
                        else -> {
                            PillAction(Icons.Default.CreateNewFolder, "New folder") { showNewFolder = true }
                            PillAction(Icons.Default.UploadFile, "Upload files") { importLauncher.launch(arrayOf("*/*")) }
                            PillAction(Icons.Default.DriveFolderUpload, "Upload folder") { uploadTreeLauncher.launch(null) }
                        }
                    }
                }
            }
        }

        // ── dialogs ──────────────────────────────────────────────────────────
        if (askDest) {
            SaveToPhoneDialog(
                itemCount = pendingCopy.size,
                onDismiss = { askDest = false; pendingCopy = emptyList() },
                onStart = { specs, algos, verify, sidecar -> askDest = false; viewModel.startOffload(context, pendingCopy, specs, algos, verify, sidecar); pendingCopy = emptyList() }
            )
        }
        if (showNewFolder) TextInputDialog("New folder", "", "Create", { showNewFolder = false }) { viewModel.createFolder(it); showNewFolder = false }
        renameTarget?.let { t -> TextInputDialog("Rename", t.name, "Rename", { renameTarget = null }) { viewModel.renameEntry(t, it); renameTarget = null } }
        if (deleteTargets.isNotEmpty()) {
            GlassDialog(
                onDismissRequest = { deleteTargets = emptyList() },
                title = { Text("Delete ${deleteTargets.size} item${if (deleteTargets.size != 1) "s" else ""}?", color = TextPrimary) },
                text = { Text("This permanently removes ${if (deleteTargets.size == 1) "\"${deleteTargets[0].name}\"" else "the selected items"} from the drive. It cannot be undone.", color = TextSecondary) },
                confirmButton = { TextButton(onClick = { viewModel.deleteEntries(deleteTargets); deleteTargets = emptyList() }) { Text("Delete", color = AccentRed) } },
                dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel", color = TextTertiary) } }
            )
        }
        propertiesOf?.let { e -> PropertiesDialog(e, driveName) { propertiesOf = null } }
        checksumOf?.let { e -> ChecksumDialog(e) { checksumOf = null } }
        infoOf?.let { e ->
            val u = DriveFileProvider.buildUri(e.inodeOid, e.path)
            if (e.fileType == FileType.IMAGE) ExifSheet(u, e.name) { infoOf = null } else MediaInfoSheet(u, e.name) { infoOf = null }
        }

        // ── bottom sheet with the file actions ───────────────────────────────
        sheetEntry?.let { e ->
            GlassSheet(onDismissRequest = { sheetEntry = null }) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Fill2), contentAlignment = Alignment.Center) {
                            getFileIconAndColor(e).let { (ic, tint) -> Icon(ic, null, tint = tint) }
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
                    if (e.fileType == FileType.AUDIO || e.fileType == FileType.VIDEO || e.fileType == FileType.IMAGE) SheetRow(Icons.Default.Info, "Info…") { sheetEntry = null; infoOf = e }
                    if (!e.isDirectory) SheetRow(Icons.Default.Tag, "Checksum…") { sheetEntry = null; checksumOf = e }
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
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = Accent, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.92f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    Column(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(20.dp)).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = if (tint == Accent) TextPrimary else tint, fontSize = 11.sp, maxLines = 1)
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
private fun GridCell(entry: FileSystemEntry, isSelected: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Fill1)
                .border(if (isSelected || playing) 2.dp else 1.dp, if (isSelected || playing) Accent else GlassBorderFaint, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.fileType == FileType.VIDEO) {
                VideoThumb(entry, Modifier.fillMaxSize())
            } else if (entry.isRaw || entry.isPsd) {
                RawImage(DriveFileProvider.buildUri(entry.inodeOid, entry.path), thumb = true, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, psd = entry.isPsd)
            } else if (entry.fileType == FileType.IMAGE) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(DriveFileProvider.buildUri(entry.inodeOid, entry.path)).size(360).crossfade(true).build(),
                    contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else if (playing) {
                Icon(if (AudioSession.player?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(44.dp))
            } else {
                Icon(
                    when (entry.fileType) {
                        FileType.DIRECTORY -> Icons.Default.Folder; FileType.VIDEO -> Icons.Default.PlayCircle; FileType.AUDIO -> Icons.Default.AudioFile
                        FileType.DOCUMENT, FileType.TEXT -> Icons.Default.Description; FileType.PDF -> Icons.Default.PictureAsPdf; FileType.ARCHIVE -> Icons.Default.FolderZip; FileType.CODE -> Icons.Default.Code
                        else -> Icons.Default.InsertDriveFile
                    },
                    null, modifier = Modifier.size(40.dp),
                    tint = when (entry.fileType) { FileType.DIRECTORY -> Accent; FileType.VIDEO -> AccentOrange; FileType.AUDIO -> AccentGreen; else -> TextTertiary }
                )
            }
            if (isSelected) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
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
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).glassOrSolid(RoundedCornerShape(18.dp), GlassLevel.Card).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = onChange, modifier = Modifier.weight(1f), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search this drive", color = TextTertiary, fontSize = 15.sp); inner() } }
        )
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close search", tint = TextTertiary, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun PropertiesDialog(e: FileSystemEntry, drive: String, onDismiss: () -> Unit) {
    fun date(ms: Long) = if (ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    GlassDialog(
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } }
    )
}

@Composable
private fun PropRow(k: String, v: String) {
    Column { Text(k.uppercase(), color = TextTertiary, fontSize = 10.sp, fontFamily = Mono); Text(v, color = TextPrimary, fontSize = 14.sp) }
}

@Composable
private fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(
        onDismissRequest = onDismiss, title = { Text(title, color = TextPrimary) },
        text = {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, shape = app.feldkit.ui.components.GlassFieldShape, colors = app.feldkit.ui.components.glassFieldColors(), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(confirm, color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextTertiary) } }
    )
}

private val ImageExt = setOf("iso", "img", "dmg", "udf")

/** Shares the selected files (folders are skipped: there is nothing to attach). */
private fun shareMany(ctx: android.content.Context, items: List<FileSystemEntry>) {
    val files = items.filter { !it.isDirectory }
    if (files.isEmpty()) { android.widget.Toast.makeText(ctx, "Folders can't be shared; open them and select files", android.widget.Toast.LENGTH_SHORT).show(); return }
    val uris = ArrayList<android.net.Uri>(files.map { app.feldkit.provider.DriveFileProvider.buildUri(it.inodeOid, it.path) })
    val single = files.size == 1
    val i = android.content.Intent(if (single) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE).apply {
        type = if (single) mimeOf(files[0]) else "*/*"
        if (single) putExtra(android.content.Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(i, "Share"))
}

private fun mimeOf(e: FileSystemEntry): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension) ?: "*/*"
