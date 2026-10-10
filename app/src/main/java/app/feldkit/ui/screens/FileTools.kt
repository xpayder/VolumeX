package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassDialog
import app.feldkit.ui.components.GlassSheet
import app.feldkit.ui.components.GlassMenu
import app.feldkit.ui.components.GlassMenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import app.feldkit.storage.ActiveDriveSession
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

private val Mono = FontFamily.Monospace

/** Hashes a file straight off the drive (xxHash, MD5, SHA family, C4, CRC32) so a copy can be verified against its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChecksumDialog(entry: FileSystemEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var algo by remember { mutableStateOf(app.feldkit.hash.HashAlgo.SHA256) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(algo) {
        result = null; error = null; progress = 0f
        withContext(Dispatchers.IO) {
            val reader = ActiveDriveSession.reader
            if (reader == null) { error = "Drive not available"; return@withContext }
            val hasher = algo.create()
            val sink = object : OutputStream() {
                override fun write(b: Int) { hasher.update(byteArrayOf(b.toByte()), 0, 1) }
                override fun write(b: ByteArray, off: Int, len: Int) { hasher.update(b, off, len) }
            }
            val ok = reader.readFileTo(entry, sink) { done -> if (entry.size > 0) progress = (done.toFloat() / entry.size).coerceIn(0f, 1f) }
            if (!ok) { error = "Could not read the file"; hasher.release() } else { result = algo.format(hasher.digest()); hasher.release() }
        }
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Checksum", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.name, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.feldkit.hash.HashAlgo.values().forEach { a ->
                        Text(
                            a.label, color = if (a == algo) DeepNavy else TextSecondary, fontSize = 12.sp, fontFamily = Mono,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (a == algo) Accent else Color0x1F).clickable { algo = a }.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
                when {
                    error != null -> Text(error!!, color = AccentRed, fontSize = 13.sp)
                    result == null -> { LinearProgressIndicator(progress = { progress }, color = Accent, trackColor = Fill2, modifier = Modifier.fillMaxWidth()); Text("${(progress * 100).toInt()}%", color = TextTertiary, fontSize = 12.sp, fontFamily = Mono) }
                    else -> {
                        Text(result!!, color = TextPrimary, fontSize = 12.sp, fontFamily = Mono, lineHeight = 17.sp)
                        TextButton(onClick = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(algo.label, result)) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = Accent); Spacer(Modifier.width(6.dp)); Text("Copy", color = Accent)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Accent) } }
    )
}

private val Color0x1F = androidx.compose.ui.graphics.Color(0x1FFFFFFF)

// ---------------------------------------------------------------------------------------------------------------------

private class InfoRow(val label: String, val value: String)
private class InfoGroup(val title: String, val rows: List<InfoRow>)

private fun hasDecoder(mime: String): Boolean =
    try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } } } catch (_: Exception) { false }

private fun friendly(mime: String) = when (mime.lowercase()) {
    "video/avc" -> "H.264 / AVC"; "video/hevc" -> "H.265 / HEVC"; "video/av01" -> "AV1"; "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4 Visual"; "video/mpeg2" -> "MPEG-2"; "video/3gpp" -> "H.263"; "video/dolby-vision" -> "Dolby Vision"
    "audio/mp4a-latm" -> "AAC"; "audio/mpeg" -> "MP3"; "audio/flac" -> "FLAC"; "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/raw" -> "PCM (WAV / AIFF)"
    "audio/alac" -> "Apple Lossless (ALAC)"; "audio/ac3" -> "Dolby Digital"; "audio/eac3" -> "Dolby Digital Plus"; "audio/amr-wb", "audio/3gpp" -> "AMR"
    else -> mime
}

/** AIFF / AIFF-C: Android cannot probe it, so read the COMM chunk ourselves. */
private fun aiffInfo(ctx: Context, uri: Uri): List<InfoGroup>? {
    val head = ByteArray(1 shl 16); var n = 0
    ctx.contentResolver.openInputStream(uri)?.use { ins -> while (n < head.size) { val r = ins.read(head, n, head.size - n); if (r <= 0) break; n += r } } ?: return null
    fun be16(o: Int) = ((head[o].toInt() and 0xFF) shl 8) or (head[o + 1].toInt() and 0xFF)
    fun be32(o: Int) = (be16(o).toLong() shl 16) or be16(o + 2).toLong()
    if (n < 12 || String(head, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
    val form = String(head, 8, 4, Charsets.ISO_8859_1)
    var p = 12
    while (p + 8 <= n) {
        val id = String(head, p, 4, Charsets.ISO_8859_1); val size = be32(p + 4)
        if (id == "COMM" && p + 26 <= n) {
            val ch = be16(p + 8); val frames = be32(p + 10); val bits = be16(p + 14)
            val exp = be16(p + 16) and 0x7FFF
            val rate = (be32(p + 18).toDouble() * 4294967296.0 + be32(p + 22).toDouble()) * Math.pow(2.0, (exp - 16383 - 63).toDouble())
            val comp = if (form == "AIFC" && p + 34 <= n) String(head, p + 26, 4, Charsets.ISO_8859_1) else "NONE"
            val rows = listOf(
                InfoRow("Codec", "PCM (${if (form == "AIFC") "AIFF-C $comp" else "AIFF"})"),
                InfoRow("Sample rate", "%.1f kHz".format(rate / 1000.0)), InfoRow("Bit depth", if (comp.startsWith("fl")) "$bits-bit float" else "$bits-bit"),
                InfoRow("Channels", when (ch) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$ch" }),
                InfoRow("Duration", fmt((frames * 1000.0 / rate).toLong())), InfoRow("Bitrate", "%.0f kbps".format(rate * bits * ch / 1000.0)),
                InfoRow("Plays on this phone", "Yes (FeldKit converts AIFF while playing)")
            )
            return listOf(InfoGroup("Audio track", rows))
        }
        p = (p + 8 + size + (size and 1)).toInt()
    }
    return null
}

/** MIDI: what the file contains and how FeldKit will play it (the system decoder's numbers would describe something else). */
private fun midiInfo(ctx: Context, uri: Uri): List<InfoGroup>? {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val song = app.feldkit.audio.midi.Smf.parse(bytes) ?: return null
    val hint = (uri.getQueryParameter("path") ?: "").split('/').takeLast(2).joinToString(" ")
    val verdict = app.feldkit.audio.midi.MidiClassifier.classify(song, hint)
    val parts = song.notes.groupBy { it.channel }.toSortedMap().map { (ch, ns) ->
        val what = if (ch in verdict.drums) "drum kit" + (verdict.remap[ch]?.let { " (${ns.size} hits of one drum)" } ?: "") else "piano"
        InfoRow("Channel ${ch + 1}", "$what · ${ns.size} notes")
    }
    val rows = listOf(
        InfoRow("Format", "Standard MIDI file"),
        InfoRow("Duration", fmt((song.lengthSeconds * 1000).toLong())),
        InfoRow("Notes", "${song.notes.size}"),
        song.timeSignature?.let { InfoRow("Time signature", "${it.first}/${it.second}") },
        InfoRow("Plays on this phone", "Yes - FeldKit's own piano and drum kit"),
    ).filterNotNull()
    return listOf(InfoGroup("MIDI", rows), InfoGroup("Parts", parts))
}

private fun readInfo(ctx: Context, uri: Uri): List<InfoGroup> {
    val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase()
    if (ext == "mid" || ext == "midi" || ext == "kar") midiInfo(ctx, uri)?.let { return it }
    if (ext == "aif" || ext == "aiff" || ext == "aifc") aiffInfo(ctx, uri)?.let { return it }
    val groups = ArrayList<InfoGroup>()
    val r = MediaMetadataRetriever()
    try { r.setDataSource(ctx, uri) } catch (_: Exception) {}
    fun m(k: Int) = try { r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    val general = listOfNotNull(
        m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { InfoRow("Container", it) },
        m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { InfoRow("Duration", fmt(it)) },
        m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { InfoRow("Overall bitrate", "%.0f kbps".format(it / 1000.0)) },
        m(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { InfoRow("Date", it) }
    )
    if (general.isNotEmpty()) groups.add(InfoGroup("General", general))
    val tags = listOfNotNull(
        m(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { InfoRow("Title", it) }, m(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { InfoRow("Artist", it) },
        m(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { InfoRow("Album", it) }, m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let { InfoRow("Album artist", it) },
        m(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.let { InfoRow("Composer", it) }, m(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { InfoRow("Genre", it) },
        m(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { InfoRow("Year", it) }, m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { InfoRow("Track", it) }
    )
    if (tags.isNotEmpty()) groups.add(InfoGroup("Tags", tags))
    try { r.release() } catch (_: Exception) {}
    val ex = MediaExtractor()
    try {
        ex.setDataSource(ctx, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            fun int(k: String) = if (f.containsKey(k)) f.getInteger(k) else null
            val rows = ArrayList<InfoRow>()
            rows.add(InfoRow("Codec", friendly(mime)))
            if (mime.startsWith("video/")) {
                val w = int(MediaFormat.KEY_WIDTH); val h = int(MediaFormat.KEY_HEIGHT)
                if (w != null && h != null) rows.add(InfoRow("Resolution", "$w × $h"))
                try { if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) rows.add(InfoRow("Frame rate", "%.3f fps".format(f.getFloat(MediaFormat.KEY_FRAME_RATE)))) } catch (_: Exception) { int(MediaFormat.KEY_FRAME_RATE)?.let { rows.add(InfoRow("Frame rate", "$it fps")) } }
                int(MediaFormat.KEY_BIT_RATE)?.let { rows.add(InfoRow("Bitrate", "%.1f Mbps".format(it / 1e6))) }
                int(MediaFormat.KEY_ROTATION)?.takeIf { it != 0 }?.let { rows.add(InfoRow("Rotation", "$it°")) }
                if (f.containsKey("color-standard")) rows.add(InfoRow("Colour", when (int("color-standard")) { 1 -> "BT.709"; 2 -> "BT.601"; 6 -> "BT.2020"; else -> "standard ${int("color-standard")}" } + (if (int("color-transfer") == 6 || int("color-transfer") == 7) " HDR" else "")))
            } else if (mime.startsWith("audio/")) {
                int(MediaFormat.KEY_SAMPLE_RATE)?.let { rows.add(InfoRow("Sample rate", "%.1f kHz".format(it / 1000.0))) }
                int(MediaFormat.KEY_CHANNEL_COUNT)?.let { rows.add(InfoRow("Channels", when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$it" })) }
                int(MediaFormat.KEY_BIT_RATE)?.let { rows.add(InfoRow("Bitrate", "$it bps".let { _ -> "%.0f kbps".format(it / 1000.0) })) }
                int(MediaFormat.KEY_PCM_ENCODING)?.let { rows.add(InfoRow("Sample format", when (it) { 2 -> "16-bit PCM"; 3 -> "8-bit PCM"; 4 -> "32-bit float"; 21 -> "24-bit PCM"; 22 -> "32-bit PCM"; else -> "encoding $it" })) }
            }
            f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" }?.let { rows.add(InfoRow("Language", it)) }
            rows.add(InfoRow("Plays on this phone", if (hasDecoder(mime)) "Yes (decoder available)" else "No decoder for this codec - open it with another app"))
            groups.add(InfoGroup(if (mime.startsWith("video/")) "Video track" else if (mime.startsWith("audio/")) "Audio track" else "Track ${i + 1}", rows))
        }
    } catch (_: Exception) {} finally { try { ex.release() } catch (_: Exception) {} }
    return groups
}

/** Audio / video details: container, codec, resolution, frame rate, sample rate, bit depth, tags, and whether this phone can decode it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(uri: Uri, name: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val groups by produceState<List<InfoGroup>?>(null, uri) { value = withContext(Dispatchers.IO) { try { readInfo(ctx, uri) } catch (_: Exception) { emptyList() } } }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            val ext = uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext in AudioTempoExt) TempoSection(uri)
            val g = groups
            when {
                g == null -> CircularProgressIndicator(color = Accent, modifier = Modifier.padding(16.dp).size(24.dp))
                g.isEmpty() -> Text("Android cannot read this file's media information.", color = TextTertiary, fontSize = 14.sp)
                else -> g.forEach { grp ->
                    Text(grp.title.uppercase(), color = Accent, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    grp.rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Text(r.label, color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                            Text(r.value, color = TextPrimary, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

/** Plain hex dump of the first 256 KB: for unknown formats, binaries, firmware and anything with no viewer. */
@Composable
fun HexViewer(uri: Uri, modifier: Modifier = Modifier) = app.feldkit.ui.components.ViewerPanel(modifier) { HexViewerBody(uri, Modifier.fillMaxSize()) }

@Composable
private fun HexViewerBody(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    var data by remember(uri) { mutableStateOf<ByteArray?>(null) }
    var truncated by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val cap = 256 * 1024; val buf = ByteArray(cap); var n = 0
                ctx.contentResolver.openInputStream(uri)?.use { ins -> while (n < cap) { val r = ins.read(buf, n, cap - n); if (r <= 0) break; n += r }; truncated = ins.read() >= 0 }
                data = buf.copyOf(n)
            } catch (_: Exception) { data = ByteArray(0) }
        }
    }
    val d = data
    if (d == null) { Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return }
    val rows = (d.size + 7) / 8
    LazyColumn(modifier.padding(horizontal = 6.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)) {
        items(rows) { i ->
            val o = i * 8
            val sb = StringBuilder("%08x  ".format(o))
            for (k in 0 until 8) { if (o + k < d.size) sb.append("%02x ".format(d[o + k])) else sb.append("   ") }
            sb.append(' ')
            for (k in 0 until 8) if (o + k < d.size) { val c = d[o + k].toInt() and 0xFF; sb.append(if (c in 32..126) c.toChar() else '.') }
            Text(sb.toString(), color = TextPrimary, fontSize = 11.sp, fontFamily = Mono, lineHeight = 14.sp, maxLines = 1, softWrap = false)
        }
        if (truncated) item { Text("Showing the first 256 KB.", color = AccentOrange, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

/** Photoshop (.psd): shows the flattened composite that "Maximize compatibility" stores (8/16-bit RGB or greyscale, raw or RLE). */
object PsdPreview {
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    fun load(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? {
        ctx.contentResolver.openInputStream(uri)?.buffered(1 shl 20)?.use { ins ->
            fun readN(n: Int): ByteArray? { val b = ByteArray(n); var o = 0; while (o < n) { val r = ins.read(b, o, n - o); if (r <= 0) return null; o += r }; return b }
            fun skipN(n: Long): Boolean { var left = n; while (left > 0) { val s = ins.skip(left); if (s <= 0) { if (ins.read() < 0) return false; left-- } else left -= s }; return true }
            val h = readN(26) ?: return null
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "8BPS" || u16(h, 4) != 1) return null
            val ch = u16(h, 12); val height = u32(h, 14).toInt(); val width = u32(h, 18).toInt(); val depth = u16(h, 22); val mode = u16(h, 24)
            if (!(mode == 3 || mode == 1) || (depth != 8 && depth != 16) || width <= 0 || height <= 0 || ch < (if (mode == 3) 3 else 1)) return null
            for (i in 0 until 3) { val l = u32(readN(4) ?: return null, 0); if (!skipN(l)) return null }      // colour data, image resources, layers
            val comp = u16(readN(2) ?: return null, 0)
            if (comp != 0 && comp != 1) return null
            val bps = depth / 8
            val stride = maxOf(1, (maxOf(width, height) + maxEdge - 1) / maxEdge)
            val ow = (width + stride - 1) / stride; val oh = (height + stride - 1) / stride
            val used = if (mode == 3) 3 else 1
            val planes = Array(used) { ByteArray(ow * oh) }
            val counts = IntArray(ch * height)
            if (comp == 1) { val t = readN(ch * height * 2) ?: return null; for (i in counts.indices) counts[i] = u16(t, i * 2) }
            val row = ByteArray(width * bps)
            for (c in 0 until ch) for (y in 0 until height) {
                val keep = c < used && y % stride == 0
                if (comp == 0) { if (keep) { val r = readN(width * bps) ?: return null; System.arraycopy(r, 0, row, 0, r.size) } else if (!skipN((width * bps).toLong())) return null }
                else {
                    val len = counts[c * height + y]
                    if (!keep) { if (!skipN(len.toLong())) return null; continue }
                    val src = readN(len) ?: return null
                    var si = 0; var di = 0
                    while (si < len && di < row.size) {                                                // PackBits
                        val n = src[si++].toInt()
                        if (n >= 0) { val cnt = n + 1; for (k in 0 until cnt) if (di < row.size && si < len) row[di++] = src[si++] }
                        else if (n != -128) { val cnt = 1 - n; val v = if (si < len) src[si++] else 0; for (k in 0 until cnt) if (di < row.size) row[di++] = v }
                    }
                }
                if (keep) { var oi = (y / stride) * ow; var x = 0; while (x < width) { planes[c][oi++] = row[x * bps]; x += stride } }
            }
            val px = IntArray(ow * oh)
            for (i in px.indices) {
                val r = planes[0][i].toInt() and 0xFF
                val g = if (used == 3) planes[1][i].toInt() and 0xFF else r
                val b = if (used == 3) planes[2][i].toInt() and 0xFF else r
                px[i] = AColor.rgb(r, g, b)
            }
            return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        }
        return null
    }
}


private val AudioTempoExt = setOf("mp3", "aac", "flac", "wav", "m4a", "ogg", "oga", "opus", "wma", "mka", "mid", "midi", "kar", "amr", "aif", "aiff", "aifc", "ac3", "weba", "m4b", "caf")

/** Tempo block of the media info sheet: measured BPM (or the exact tempo of a MIDI file), alternatives and the file's own tag. */
@Composable
private fun TempoSection(uri: Uri) {
    val ctx = LocalContext.current
    val info by produceState<app.feldkit.audio.TempoInfo?>(null, uri) { value = withContext(Dispatchers.IO) { try { app.feldkit.audio.TempoService.analyze(ctx, uri) } catch (_: Throwable) { null } } }
    Text("TEMPO", color = Accent, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    val i = info
    if (i == null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp)); Text("Measuring the tempo…", color = TextTertiary, fontSize = 13.sp)
        }
        return
    }
    val r = i.result
    fun fmtBpm(b: Double) = if (b == Math.rint(b)) "%.0f".format(b) else "%.2f".format(b)
    if (r != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
            Text("BPM", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtBpm(r.bpm), color = Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
                val note = when {
                    i.fromFile -> if (i.variable) "from the file's tempo map (changes during the song)" else "stored in the MIDI file"
                    r.loopBeats > 0 -> "loop of ${r.loopBeats} beats, ${r.loopBeats / 4} bar${if (r.loopBeats / 4 != 1) "s" else ""}"
                    r.steady -> "steady tempo"
                    else -> "average tempo (it drifts)"
                }
                Text(note, color = TextTertiary, fontSize = 12.sp)
            }
        }
        if (r.alternates.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text("Also reads as", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Text(r.alternates.joinToString("  /  ") { fmtBpm(it) }, color = TextPrimary, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text("BPM", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Text(if (i.unsupported) "This format cannot be analysed on the phone" else "No steady beat found", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
    i.tag?.let { t ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text("In the file", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Text("${fmtBpm(t.bpm)}  (${t.source})", color = TextPrimary, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
        }
    }
}
