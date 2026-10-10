package app.feldkit.ui.screens

import app.feldkit.ui.components.GlassDialog
import app.feldkit.ui.components.GlassSheet
import app.feldkit.ui.components.GlassMenu
import app.feldkit.ui.components.GlassMenuItem
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import app.feldkit.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Camera RAW support: shows the full-size JPEG preview that nearly every RAW file embeds. */
object RawPreview {
    private val cache = object : android.util.LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }

    private fun orientationMatrix(o: Int): Matrix? {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return null
        }
        return m
    }

    /** Offset/length of the JPEG that starts at [start], or null if it is not a complete JPEG. */
    private fun jpegEnd(b: ByteArray, start: Int): Int {
        var p = start + 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) return -1
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xD9) return p + 2
            if (m == 0x01 || m in 0xD0..0xD7 || m == 0xFF) { p += if (m == 0xFF) 1 else 2; continue }
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (m == 0xDA) {                                  // entropy-coded data until the next real marker
                var q = p + 2 + len
                while (q + 1 < b.size) {
                    if (b[q] == 0xFF.toByte()) { val n = b[q + 1].toInt() and 0xFF; if (n == 0xD9) return q + 2; if (n != 0 && n !in 0xD0..0xD7) { p = q; break } }
                    q++
                }
                if (q + 1 >= b.size) return -1
                continue
            }
            p += 2 + len
        }
        return -1
    }

    private fun largestEmbeddedJpeg(ctx: Context, uri: Uri): ByteArray? {
        val head = ByteArray(10 * 1024 * 1024); var n = 0
        ctx.contentResolver.openInputStream(uri)?.use { ins -> while (n < head.size) { val r = ins.read(head, n, head.size - n); if (r <= 0) break; n += r } } ?: return null
        val b = head.copyOf(n)
        var best: IntArray? = null
        var i = 0
        while (i + 4 < b.size) {
            if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte() && b[i + 2] == 0xFF.toByte()) {
                val e = jpegEnd(b, i)
                if (e > i && (best == null || e - i > best[1] - best[0]) && e - i > 30_000) best = intArrayOf(i, e)
                if (e > i) { i = e; continue }
            }
            i++
        }
        return best?.let { b.copyOfRange(it[0], it[1]) }
    }

    /** [thumb] = the small embedded thumbnail (grid tiles); otherwise the large preview. */
    suspend fun load(ctx: Context, uri: Uri, thumb: Boolean, psd: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri#$thumb#$psd"
        if (psd) {
            cache.get(key)?.let { return@withContext it }
            return@withContext try { PsdPreview.load(ctx, uri, if (thumb) 512 else 3072)?.also { cache.put(key, it) } } catch (_: Throwable) { null }
        }
        cache.get(key)?.let { return@withContext it }
        try {
            val orientation = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
            var bmp: Bitmap? = null
            if (thumb) bmp = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).thumbnailBitmap }
            if (bmp == null) {
                val bytes = largestEmbeddedJpeg(ctx, uri) ?: return@withContext null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                val target = if (thumb) 512 else 4096
                while (maxOf(o.outWidth, o.outHeight) / sample > target) sample *= 2
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val m = orientationMatrix(orientation)
            val out = if (bmp != null && m != null) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) else bmp
            out?.also { cache.put(key, it) }
        } catch (_: Exception) { null }
    }
}

/** A RAW photo (shown through its embedded preview). */
@Composable
fun RawImage(uri: Uri, thumb: Boolean, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, psd: Boolean = false) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, thumb, psd) { value = RawPreview.load(ctx, uri, thumb, psd) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = contentScale)
    else Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
}

private class ExifRow(val label: String, val value: String)
private class ExifGroup(val title: String, val rows: List<ExifRow>)

private fun readExif(ctx: Context, uri: Uri): List<ExifGroup> {
    val e = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).let { x -> x } } ?: return emptyList()
    fun a(tag: String) = e.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
    fun rat(tag: String): Double? {
        e.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() }?.let { return it }
        val t = a(tag) ?: return null                     // "n/d", "n,d" or a plain number, depending on how the writer typed it
        val parts = t.split('/', ',').mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) { 1 -> parts[0]; 2 -> if (parts[1] != 0.0) parts[0] / parts[1] else null; else -> null }
    }
    val camera = listOfNotNull(
        listOfNotNull(a(ExifInterface.TAG_MAKE), a(ExifInterface.TAG_MODEL)).joinToString(" ").ifEmpty { null }?.let { ExifRow("Camera", it) },
        a(ExifInterface.TAG_LENS_MODEL)?.let { ExifRow("Lens", it) },
        a(ExifInterface.TAG_SOFTWARE)?.let { ExifRow("Software", it) }
    )
    val shutter = rat(ExifInterface.TAG_EXPOSURE_TIME)?.let { t -> if (t >= 1) "%.1f s".format(t) else "1/${Math.round(1.0 / t)} s" }
    val exposure = listOfNotNull(
        rat(ExifInterface.TAG_F_NUMBER)?.let { ExifRow("Aperture", "ƒ/%.1f".format(it)) },
        shutter?.let { ExifRow("Shutter", it) },
        (a(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: a("ISOSpeedRatings"))?.let { ExifRow("ISO", it) },
        rat(ExifInterface.TAG_FOCAL_LENGTH)?.let { f -> ExifRow("Focal length", "%.0f mm".format(f) + (a(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { " (35 mm: $it)" } ?: "")) },
        rat(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { ExifRow("Exposure comp.", "%+.1f EV".format(it)) },
        a(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { ExifRow("Flash", if (it and 1 == 1) "Fired" else "Off") },
        a(ExifInterface.TAG_WHITE_BALANCE)?.toIntOrNull()?.let { ExifRow("White balance", if (it == 0) "Auto" else "Manual") }
    )
    var w = (a(ExifInterface.TAG_IMAGE_WIDTH) ?: a(ExifInterface.TAG_PIXEL_X_DIMENSION))?.takeIf { it != "0" }
    var h = (a(ExifInterface.TAG_IMAGE_LENGTH) ?: a(ExifInterface.TAG_PIXEL_Y_DIMENSION))?.takeIf { it != "0" }
    if (w == null || h == null) {                         // no size tags: read it from the image itself
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0) { w = o.outWidth.toString(); h = o.outHeight.toString() }
    }
    val image = listOfNotNull(
        if (w != null && h != null) ExifRow("Dimensions", "$w × $h" + (w.toLongOrNull()?.times(h.toLongOrNull() ?: 0)?.let { " · %.1f MP".format(it / 1e6) } ?: "")) else null,
        a(ExifInterface.TAG_COLOR_SPACE)?.toIntOrNull()?.let { ExifRow("Colour space", if (it == 1) "sRGB" else "Other") }
    )
    val whenWhere = listOfNotNull(
        (a(ExifInterface.TAG_DATETIME_ORIGINAL) ?: a(ExifInterface.TAG_DATETIME))?.let { ExifRow("Taken", it.replaceFirst(':', '-').replaceFirst(':', '-')) },
        e.latLong?.let { ExifRow("Location", "%.5f, %.5f".format(it[0], it[1])) },
        e.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { ExifRow("Altitude", "%.0f m".format(it)) }
    )
    return listOf(ExifGroup("Camera", camera), ExifGroup("Exposure", exposure), ExifGroup("Image", image), ExifGroup("When & where", whenWhere)).filter { it.rows.isNotEmpty() }
}

/** Bottom sheet with the photo's EXIF data (camera, exposure, size, time, GPS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifSheet(uri: Uri, name: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val groups by produceState<List<ExifGroup>?>(null, uri) { value = withContext(Dispatchers.IO) { try { readExif(ctx, uri) } catch (_: Exception) { emptyList() } } }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(14.dp))
            val g = groups
            when {
                g == null -> CircularProgressIndicator(color = Accent, modifier = Modifier.padding(16.dp).size(24.dp))
                g.isEmpty() -> Text("This file carries no EXIF data.", color = TextTertiary, fontSize = 14.sp)
                else -> g.forEach { grp ->
                    Text(grp.title.uppercase(), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    grp.rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Text(r.label, color = TextTertiary, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                            Text(r.value, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
