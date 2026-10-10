package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassDialog
import app.feldkit.ui.components.GlassSheet
import app.feldkit.ui.components.GlassMenu
import app.feldkit.ui.components.GlassMenuItem
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import app.feldkit.provider.DriveFileProvider
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileType
import app.feldkit.ui.components.Zoomable
import app.feldkit.ui.theme.*
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private fun uriOf(e: FileSystemEntry): Uri = DriveFileProvider.buildUri(e.inodeOid, e.path)
private fun mimeOf(e: FileSystemEntry) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension) ?: "*/*"

private fun openExternal(ctx: android.content.Context, e: FileSystemEntry) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(e), mimeOf(e)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, "Open with")) } catch (_: Exception) { android.widget.Toast.makeText(ctx, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun share(ctx: android.content.Context, e: FileSystemEntry) {
    val i = Intent(Intent.ACTION_SEND).apply { type = mimeOf(e); putExtra(Intent.EXTRA_STREAM, uriOf(e)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePreviewScreen(
    entry: FileSystemEntry,
    siblings: List<FileSystemEntry> = listOf(entry),
    canDelete: Boolean = false,
    onDelete: (FileSystemEntry) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val items = remember(entry, siblings) { if (siblings.any { it.path == entry.path }) siblings else listOf(entry) }
    val startIndex = remember(entry, items) { items.indexOfFirst { it.path == entry.path }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    var mediaIndex by remember(entry) { mutableIntStateOf(startIndex) }
    val isImage = entry.fileType == FileType.IMAGE
    val current = if (isImage) items[pager.currentPage.coerceIn(0, items.lastIndex)] else items[mediaIndex.coerceIn(0, items.lastIndex)]
    val position = if (isImage) pager.currentPage else mediaIndex
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var failed by remember(current.path) { mutableStateOf(false) }
    var showExif by remember { mutableStateOf(false) }
    var showMediaInfo by remember { mutableStateOf(false) }
    var hexMode by remember(current.path) { mutableStateOf(false) }

    // landscape + hidden system bars while a video is fullscreen
    val activity = context as? android.app.Activity
    val originalLandscape = remember { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (fullscreen) {
                // go back to the orientation we came from, then hand rotation back to the sensor (a phone lying flat has no sensor to do it)
                activity?.requestedOrientation = if (originalLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, 700)
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    val loader = remember {
        ImageLoader.Builder(context).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(SvgDecoder.Factory())
        }.build()
    }
    val hazeState = LocalHaze.current ?: rememberHazeState()
    var headerPx by remember { mutableIntStateOf(0) }
    val headerDp = with(LocalDensity.current) { headerPx.toDp() }
    val type = current.fileType

    Box(modifier = Modifier.fillMaxSize()) {
        if (!fullscreen && (chrome || !isImage)) app.feldkit.ui.components.GlassTopBar(
            title = current.name,
            subtitle = if (items.size > 1) "${position + 1} of ${items.size} · ${current.formattedSize}" else current.formattedSize,
            onBack = onNavigateBack,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).onSizeChanged { headerPx = it.height },
            actions = {
                if (type == FileType.AUDIO || type == FileType.VIDEO) IconButton(onClick = { showMediaInfo = true }) { Icon(Icons.Default.Info, "Media info", tint = TextSecondary) }
                if (type == FileType.IMAGE) IconButton(onClick = { showExif = true }) { Icon(Icons.Default.Info, "Photo info", tint = TextSecondary) }
                IconButton(onClick = { openExternal(context, current) }) { Icon(Icons.Default.OpenInNew, "Open with", tint = TextSecondary) }
                IconButton(onClick = { share(context, current) }) { Icon(Icons.Default.Share, "Share", tint = TextSecondary) }
                if (canDelete) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = TextSecondary) }
            }
        )

        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            val pad = if (fullscreen) 0.dp else headerDp
            val uri = remember(current.inodeOid, current.path) { uriOf(current) }
            val prev: (() -> Unit)? = if (mediaIndex > 0) ({ mediaIndex-- }) else null
            val next: (() -> Unit)? = if (mediaIndex < items.lastIndex) ({ mediaIndex++ }) else null
            when {
                isImage -> HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = { items[it].path }) { page ->
                    val e = items[page]
                    if (e.fileType == FileType.IMAGE) {
                        Zoomable(Modifier.fillMaxSize(), onTap = { chrome = !chrome }, onZoomedChange = { if (page == pager.currentPage) zoomed = it }) {
                            if (e.isRaw || e.isPsd) RawImage(uriOf(e), thumb = false, modifier = Modifier.fillMaxSize(), psd = e.isPsd)
                            else AsyncImage(
                                model = ImageRequest.Builder(context).data(uriOf(e)).crossfade(true).build(), imageLoader = loader,
                                contentDescription = e.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else OpenWithView(e, null, { openExternal(context, e) }, { share(context, e) }, Modifier.fillMaxSize().padding(top = headerDp))
                }
                type == FileType.VIDEO -> VideoPlayerView(
                    uri = uri, modifier = Modifier.fillMaxSize().padding(top = pad), onPrev = prev, onNext = next,
                    onFullscreen = { fullscreen = !fullscreen }, fullscreen = fullscreen, onOpenExternal = { openExternal(context, current) }
                )
                type == FileType.AUDIO -> Box(Modifier.fillMaxSize().padding(top = pad), contentAlignment = Alignment.Center) {
                    AudioPlayerCard(uri, current.name, current.formattedSize, prev, next) { openExternal(context, current) }
                }
                type == FileType.PDF && !failed -> PdfViewer(uri, Modifier.fillMaxSize().padding(top = pad), onFail = { failed = true })
                (type == FileType.TEXT || type == FileType.CODE) && !failed -> TextViewer(uri, Modifier.fillMaxSize().padding(top = pad), lineNumbers = type == FileType.CODE)
                type == FileType.ARCHIVE -> if (!failed && ArchiveSession.kindOf(current.name) != null) ArchiveViewer(current, uri, Modifier.fillMaxSize().padding(top = pad), onFail = { failed = true }) else OpenWithView(current, "This archive format needs another app.", { openExternal(context, current) }, { share(context, current) }, Modifier.fillMaxSize().padding(top = pad))
                hexMode -> HexViewer(uri, Modifier.fillMaxSize().padding(top = pad))
                else -> OpenWithView(
                    current,
                    when (type) { FileType.ARCHIVE -> "FeldKit opens zip, 7z, tar, gz, bz2, xz and rar archives."; FileType.DOCUMENT -> "Open this document in an app that supports it."; else -> "FeldKit has no built-in viewer for this file type." },
                    { openExternal(context, current) }, { share(context, current) }, Modifier.fillMaxSize().padding(top = pad), onHex = { hexMode = true }
                )
            }
        }

        if (showMediaInfo) MediaInfoSheet(uri = uriOf(current), name = current.name) { showMediaInfo = false }
        if (showExif) ExifSheet(uri = uriOf(current), name = current.name) { showExif = false }

        if (confirmDelete) {
            GlassDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete \"${current.name}\"?", color = TextPrimary) },
                text = { Text("This permanently removes it from the drive.", color = TextSecondary) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(current); onNavigateBack() }) { Text("Delete", color = AccentRed) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextTertiary) } }
            )
        }
    }
}
