package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassOutlinedButton
import app.feldkit.ui.components.GlassButton
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One archive on the drive, copied to the app cache so it can be opened random-access. */
class ArchiveSession private constructor(private val file: File, val kind: Kind, val displayName: String) {
    enum class Kind { ZIP, SEVENZ, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ, RAR }
    class Item(val path: String, val size: Long, val isDir: Boolean)

    companion object {
        /** For tests: wraps an archive that is already a local file. */
        fun fromFile(file: File, name: String): ArchiveSession? = kindOf(name)?.let { ArchiveSession(file, it, name) }

        fun kindOf(name: String): Kind? {
            val n = name.lowercase()
            return when {
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Kind.TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR_XZ
                n.endsWith(".tar") -> Kind.TAR
                n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".aab") || n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx") || n.endsWith(".epub") -> Kind.ZIP
                n.endsWith(".7z") -> Kind.SEVENZ
                n.endsWith(".rar") -> Kind.RAR
                n.endsWith(".gz") -> Kind.GZ
                n.endsWith(".bz2") -> Kind.BZ2
                n.endsWith(".xz") -> Kind.XZ
                else -> null
            }
        }

        /** Copies the archive from the drive into the cache (the formats need seeking), reporting progress. */
        suspend fun open(ctx: Context, uri: Uri, name: String, size: Long, progress: (Long) -> Unit): ArchiveSession? = withContext(Dispatchers.IO) {
            val kind = kindOf(name) ?: return@withContext null
            val f = File(ctx.cacheDir, "archive-${System.nanoTime()}")
            ctx.contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().buffered(1 shl 20).use { o -> val b = ByteArray(1 shl 20); var t = 0L; while (true) { val r = ins.read(b); if (r <= 0) break; o.write(b, 0, r); t += r; progress(t) } } } ?: return@withContext null
            ArchiveSession(f, kind, name)
        }
    }

    fun close() { file.delete() }

    private fun tarStream(): TarArchiveInputStream {
        val raw = file.inputStream().buffered(1 shl 16)
        return TarArchiveInputStream(when (kind) { Kind.TAR_GZ -> GzipCompressorInputStream(raw); Kind.TAR_BZ2 -> BZip2CompressorInputStream(raw); Kind.TAR_XZ -> XZCompressorInputStream(raw); else -> raw })
    }

    private fun singleStream(): InputStream {
        val raw = file.inputStream().buffered(1 shl 16)
        return when (kind) { Kind.GZ -> GzipCompressorInputStream(raw); Kind.BZ2 -> BZip2CompressorInputStream(raw); else -> XZCompressorInputStream(raw) }
    }

    fun list(): List<Item> = when (kind) {
        Kind.ZIP -> ZipFile.builder().setFile(file).get().use { z -> z.entries.asSequence().map { Item(it.name, it.size, it.isDirectory) }.toList() }
        Kind.SEVENZ -> SevenZFile.builder().setFile(file).get().use { z -> z.entries.map { Item(it.name, it.size, it.isDirectory) } }
        Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2, Kind.TAR_XZ -> tarStream().use { t -> generateSequence { t.nextEntry }.map { Item(it.name, it.size, it.isDirectory) }.toList() }
        Kind.GZ, Kind.BZ2, Kind.XZ -> listOf(Item(displayName.substringBeforeLast('.'), -1, false))
        Kind.RAR -> com.github.junrar.Archive(file).use { a -> a.fileHeaders.map { Item(it.fileName.replace('\\', '/'), it.fullUnpackSize, it.isDirectory) } }
    }

    /** Streams the entry [path] into [out]. */
    fun extract(path: String, out: OutputStream) {
        when (kind) {
            Kind.ZIP -> ZipFile.builder().setFile(file).get().use { z -> z.getEntry(path)?.let { z.getInputStream(it).use { i -> i.copyTo(out, 1 shl 16) } } }
            Kind.SEVENZ -> SevenZFile.builder().setFile(file).get().use { z ->
                while (true) { val e = z.nextEntry ?: break; if (e.name == path) { val b = ByteArray(1 shl 16); while (true) { val r = z.read(b); if (r <= 0) break; out.write(b, 0, r) }; break } }
            }
            Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2, Kind.TAR_XZ -> tarStream().use { t -> while (true) { val e = t.nextEntry ?: break; if (e.name == path) { t.copyTo(out, 1 shl 16); break } } }
            Kind.GZ, Kind.BZ2, Kind.XZ -> singleStream().use { it.copyTo(out, 1 shl 16) }
            Kind.RAR -> com.github.junrar.Archive(file).use { a -> a.fileHeaders.firstOrNull { it.fileName.replace('\\', '/') == path }?.let { a.extractFile(it, out) } }
        }
    }
}

/** Archive browser: lists the contents; tap an entry to extract it, or extract everything, into Downloads/FeldKit/<archive>/. */
@Composable
fun ArchiveViewer(entry: FileSystemEntry, uri: Uri, modifier: Modifier = Modifier, onFail: () -> Unit) = app.feldkit.ui.components.ViewerPanel(modifier) { ArchiveViewerBody(entry, uri, Modifier.fillMaxSize(), onFail) }

@Composable
private fun ArchiveViewerBody(entry: FileSystemEntry, uri: Uri, modifier: Modifier, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember(uri) { mutableLongStateOf(0L) }
    var session by remember(uri) { mutableStateOf<ArchiveSession?>(null) }
    var items by remember(uri) { mutableStateOf<List<ArchiveSession.Item>?>(null) }
    var message by remember(uri) { mutableStateOf<String?>(null) }
    var busy by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            val s = ArchiveSession.open(ctx, uri, entry.name, entry.size) { copied = it } ?: run { onFail(); return@LaunchedEffect }
            session = s
            // macOS metadata (AppleDouble ._ files, __MACOSX, .DS_Store) is noise on a phone
            items = withContext(Dispatchers.IO) { s.list().filter { i -> val n = i.path.substringAfterLast('/').ifEmpty { i.path.trimEnd('/').substringAfterLast('/') }; !n.startsWith("._") && n != ".DS_Store" && !i.path.startsWith("__MACOSX/") } }
        } catch (e: Throwable) {
            message = if (e.javaClass.simpleName.contains("V5")) "RAR5 archives are not supported yet" else (e.message ?: "Cannot read this archive")
            items = emptyList()
        }
    }
    DisposableEffect(uri) { onDispose { session?.close() } }

    val list = items
    val downloads = remember { File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "FeldKit") }
    // an archive that already wraps everything in one folder extracts straight into Downloads/FeldKit, otherwise into a folder named after it
    val singleRoot = list?.filter { !it.isDir }?.map { it.path.trimStart('/').substringBefore('/', "") }?.distinct()?.singleOrNull()?.isNotEmpty() == true
    val target = if (singleRoot) downloads else File(downloads, entry.name.substringBeforeLast('.').removeSuffix(".tar"))
    fun extract(list: List<ArchiveSession.Item>) {
        val s = session ?: return
        busy = true; message = null
        scope.launch(Dispatchers.IO) {
            var n = 0; var err: String? = null
            for (it in list) {
                if (it.isDir) continue
                try {
                    val rel = it.path.trimStart('/').split('/').filter { p -> p.isNotEmpty() && p != ".." }.joinToString("/")
                    if (rel.isEmpty()) continue
                    val dest = File(target, rel).also { f -> f.parentFile?.mkdirs() }
                    dest.outputStream().buffered(1 shl 20).use { o -> s.extract(it.path, o) }; n++
                } catch (e: Throwable) { err = e.message ?: "error" }
            }
            withContext(Dispatchers.Main) { busy = false; message = if (err != null && n == 0) "Extract failed: $err" else "Extracted $n file${if (n != 1) "s" else ""} to Downloads/FeldKit${if (target == downloads) "" else "/" + target.name}" }
        }
    }

    if (list == null) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val frac = if (entry.size > 0) (copied.toFloat() / entry.size).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { frac }, color = Accent, trackColor = Fill2, modifier = Modifier.width(180.dp))
            Spacer(Modifier.height(10.dp))
            Text("Reading archive from the drive…", color = TextTertiary, fontSize = 12.sp)
        }
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.count { !it.isDir }} files", color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            GlassButton(
                onClick = { extract(list) }, enabled = !busy && list.isNotEmpty(), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = DeepNavy), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (busy) CircularProgressIndicator(color = DeepNavy, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)) else Icon(Icons.Default.Unarchive, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Extract all", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        message?.let { Text(it, color = if (it.startsWith("Extracted")) AccentGreen else AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(list) { it ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !it.isDir && !busy) { extract(listOf(it)) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (it.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (it.isDir) Accent else TextTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it.path, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (it.size >= 0 && !it.isDir) Text(FileSystemEntry.formatBytes(it.size), color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
