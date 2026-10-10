package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassOutlinedButton
import app.feldkit.ui.components.GlassButton
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private val Mono = FontFamily.Monospace

/** Plain-text / source viewer: first 512 KB, selectable, line-numbered for code. */
@Composable
fun TextViewer(uri: Uri, modifier: Modifier = Modifier, lineNumbers: Boolean) = app.feldkit.ui.components.ViewerPanel(modifier) { TextViewerBody(uri, Modifier.fillMaxSize(), lineNumbers) }

@Composable
private fun TextViewerBody(uri: Uri, modifier: Modifier, lineNumbers: Boolean) {
    val ctx = LocalContext.current
    var lines by remember(uri) { mutableStateOf<List<String>?>(null) }
    var truncated by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val cap = 512 * 1024
                val buf = ByteArray(cap); var n = 0
                ctx.contentResolver.openInputStream(uri)?.use { ins -> while (n < cap) { val r = ins.read(buf, n, cap - n); if (r <= 0) break; n += r }; truncated = ins.read() >= 0 }
                lines = String(buf, 0, n, Charsets.UTF_8).split('\n')
            } catch (e: Exception) { lines = listOf("Cannot read file: ${e.message}") }
        }
    }
    val l = lines
    if (l == null) { Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return }
    SelectionContainer {
        LazyColumn(modifier.padding(horizontal = 6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)) {
            itemsIndexed(l) { i, line ->
                Row {
                    if (lineNumbers) Text("${i + 1}", color = TextDisabled, fontSize = 11.sp, fontFamily = Mono, lineHeight = 17.sp, modifier = Modifier.width(36.dp).padding(end = 8.dp))
                    Text(line.trimEnd('\r').ifEmpty { " " }, color = TextPrimary, fontSize = 12.sp, fontFamily = Mono, lineHeight = 17.sp)
                }
            }
            if (truncated) item { Text("Showing the first 512 KB. Use “Open with” for the full file.", color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }
}

/** PDF viewer on [PdfRenderer]: pages rendered on demand, pinch to change the zoom, scrolls both ways. */
@Composable
fun PdfViewer(uri: Uri, modifier: Modifier = Modifier, onFail: () -> Unit) {
    val ctx = LocalContext.current
    var renderer by remember(uri) { mutableStateOf<PdfRenderer?>(null) }
    val lock = remember { Mutex() }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try { renderer = ctx.contentResolver.openFileDescriptor(uri, "r")?.let { PdfRenderer(it) } } catch (e: Exception) { onFail() }
        }
    }
    DisposableEffect(uri) { onDispose { try { renderer?.close() } catch (_: Exception) {} } }
    val r = renderer
    if (r == null) { Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return }
    BoxWithConstraints(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.count { it.pressed } > 1) {
                        zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 3f)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
    ) {
        val density = LocalDensity.current
        val pageW = (maxWidth - 24.dp) * zoom
        val px = with(density) { pageW.roundToPx() }
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.padding(horizontal = 12.dp).width(pageW), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.pageCount) { index ->
                    var bmp by remember(index, px) { mutableStateOf<Bitmap?>(null) }
                    var ratio by remember(index) { mutableFloatStateOf(0.707f) }
                    LaunchedEffect(index, px) {
                        withContext(Dispatchers.IO) {
                            lock.withLock {
                                try {
                                    r.openPage(index).use { page ->
                                        ratio = page.width.toFloat() / page.height
                                        val b = Bitmap.createBitmap(px, (px / ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                        b.eraseColor(AColor.WHITE)
                                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        bmp = b
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        app.feldkit.ui.components.GlassPill("${(zoom * 100).toInt()}%  ·  ${r.pageCount} page${if (r.pageCount != 1) "s" else ""}", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

/** Card for formats FeldKit can't render itself (office documents, rar/7z/dmg, unknown types): hand them to another app. */
@Composable
fun OpenWithView(entry: FileSystemEntry, hint: String?, onOpen: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier, onHex: (() -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).glassOrSolid(RoundedCornerShape(26.dp), GlassLevel.Card), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(entry.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("${entry.extension.uppercase().ifEmpty { "FILE" }} · ${entry.formattedSize}", color = TextTertiary, fontSize = 13.sp, fontFamily = Mono)
        if (hint != null) { Spacer(Modifier.height(10.dp)); Text(hint, color = TextSecondary, fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
        GlassButton(onClick = onOpen, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DeepNavy)) {
            Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open with…", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        GlassOutlinedButton(onClick = onShare, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Default.Share, null, tint = TextPrimary); Spacer(Modifier.width(8.dp)); Text("Share", color = TextPrimary)
        }
        if (onHex != null) TextButton(onClick = onHex) { Text("View as hex", color = Accent) }
    }
}
