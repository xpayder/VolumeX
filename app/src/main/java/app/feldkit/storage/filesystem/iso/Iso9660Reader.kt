package app.feldkit.storage.filesystem.iso

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.VolumeInfo

/**
 * ISO 9660 (CD / DVD images, bootable installer sticks). Names come from Rock Ridge when the disc has them, otherwise from
 * Joliet, otherwise from the plain ISO names (version suffix ";1" removed). Read-only.
 */
class Iso9660Reader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    companion object {
        private const val TAG = "FeldKit"
        private const val LS = 2048
    }

    private class Rec(val name: String, val extent: Long, val size: Long, val dir: Boolean, val mtime: Long, val extents: List<Pair<Long, Long>>)

    private var rootExtent = 0L
    private var rootSize = 0L
    private var joliet = false
    private var rockRidge = false
    private var label = ""
    private var totalBlocks = 0L
    private val dirCache = HashMap<String, List<Rec>>()
    private val recCache = HashMap<String, Rec>()

    private fun readBytes(offset: Long, len: Int): ByteArray? {
        val ss = dev.sectorSize()
        val abs = startLba * ss + offset
        val first = abs / ss; val last = (abs + len - 1) / ss
        val data = dev.readSectors(first, (last - first + 1).toInt()) ?: return null
        val skip = (abs - first * ss).toInt()
        return if (skip == 0 && data.size == len) data else data.copyOfRange(skip, skip + len)
    }

    private fun u32le(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    override fun mount(): Boolean {
        var sector = 16L
        var pvd: ByteArray? = null; var svd: ByteArray? = null
        while (sector < 64) {
            val d = readBytes(sector * LS, LS) ?: break
            if (String(d, 1, 5, Charsets.ISO_8859_1) != "CD001") break          // end of the descriptor area (some writers omit the terminator)
            val type = d[0].toInt() and 0xFF
            if (type == 255) break
            if (type == 1) pvd = d
            if (type == 2 && d[88] == '%'.code.toByte() && d[89] == '/'.code.toByte() && (d[90] == '@'.code.toByte() || d[90] == 'C'.code.toByte() || d[90] == 'E'.code.toByte())) svd = d
            sector++
        }
        val p = pvd ?: return false
        totalBlocks = u32le(p, 80)
        label = String(p, 40, 32, Charsets.ISO_8859_1).trim()
        rootExtent = u32le(p, 156 + 2); rootSize = u32le(p, 156 + 10)
        // Rock Ridge is announced by an SP entry in the root's "." record
        rockRidge = detectRockRidge()
        if (!rockRidge && svd != null) {
            joliet = true
            rootExtent = u32le(svd, 156 + 2); rootSize = u32le(svd, 156 + 10)
            val jl = String(svd, 40, 32, Charsets.UTF_16BE).trim()
            if (jl.isNotEmpty()) label = jl
        }
        Log.i(TAG, "ISO 9660: label=$label rockRidge=$rockRidge joliet=$joliet blocks=$totalBlocks")
        return true
    }

    /** True when the root directory carries Rock Ridge and its entries really have alternate names (some writers add only permissions). */
    private fun detectRockRidge(): Boolean {
        val d = readBytes(rootExtent * LS, LS) ?: return false
        var p = 0; var sp = false; var nm = false; var seen = 0
        while (p < d.size && seen < 12) {
            val len = d[p].toInt() and 0xFF
            if (len == 0) break
            val nameLen = d[p + 32].toInt() and 0xFF
            var q = p + 33 + nameLen; if (nameLen % 2 == 0) q++
            while (q + 4 <= p + len) {
                val sig = String(d, q, 2, Charsets.ISO_8859_1); val l = d[q + 2].toInt() and 0xFF
                if (l < 4) break
                if (sig == "SP" && l >= 7 && (d[q + 4].toInt() and 0xFF) == 0xBE && (d[q + 5].toInt() and 0xFF) == 0xEF) sp = true
                if (sig == "NM" || sig == "CE") nm = true
                q += l
            }
            p += len; seen++
        }
        return sp && nm
    }

    override fun getVolumeInfos(): List<VolumeInfo> = listOf(
        VolumeInfo(name = label.ifEmpty { "ISO image" }, type = "ISO 9660", totalBlocks = totalBlocks, blockSize = LS.toLong(), freeKnown = true, freeBlocks = 0)
    )

    // ── directories ──

    private fun time(b: ByteArray, o: Int): Long = try {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(1900 + (b[o].toInt() and 0xFF), (b[o + 1].toInt() and 0xFF) - 1, b[o + 2].toInt() and 0xFF, b[o + 3].toInt() and 0xFF, b[o + 4].toInt() and 0xFF, b[o + 5].toInt() and 0xFF)
        }
        c.timeInMillis - (b[o + 6].toInt()) * 15 * 60 * 1000L
    } catch (_: Exception) { 0L }

    /** Rock Ridge NM (alternate name) from a record's system use area, following CE continuation areas. */
    private fun rockRidgeName(d: ByteArray, start: Int, end: Int): String? {
        val sb = StringBuilder(); var found = false
        var buf = d; var p = start; var lim = end; var hops = 0
        while (p + 4 <= lim) {
            val sig = String(buf, p, 2, Charsets.ISO_8859_1); val l = buf[p + 2].toInt() and 0xFF
            if (l < 4 || p + l > lim) break
            when (sig) {
                "NM" -> { val flags = buf[p + 4].toInt(); if (flags and 0x06 == 0) { sb.append(String(buf, p + 5, l - 5, Charsets.UTF_8)); found = true } }
                "CE" -> if (hops++ < 8) {
                    val blk = u32le(buf, p + 4); val off = u32le(buf, p + 12).toInt(); val len = u32le(buf, p + 20).toInt()
                    val more = readBytes(blk * LS + off, len) ?: break
                    buf = more; p = 0; lim = len; continue
                }
                "ST" -> return if (found) sb.toString() else null
            }
            p += l
        }
        return if (found) sb.toString() else null
    }

    private fun parseDir(extent: Long, size: Long): List<Rec> {
        val out = ArrayList<Rec>()
        val data = readBytes(extent * LS, ((size + LS - 1) / LS * LS).toInt().coerceAtMost(64 shl 20)) ?: return out
        var p = 0
        var pending: Rec? = null
        while (p < data.size) {
            val len = data[p].toInt() and 0xFF
            if (len == 0) { p = (p / LS + 1) * LS; continue }       // records never cross a sector; zero padding ends it
            if (p + len > data.size) break
            val flags = data[p + 25].toInt() and 0xFF
            val nlen = data[p + 32].toInt() and 0xFF
            val idBytes = data.copyOfRange(p + 33, p + 33 + nlen)
            val isSpecial = nlen == 1 && (idBytes[0].toInt() == 0 || idBytes[0].toInt() == 1)
            if (!isSpecial) {
                var name = if (joliet) String(idBytes, Charsets.UTF_16BE) else String(idBytes, Charsets.ISO_8859_1)
                var su = 33 + nlen; if (nlen % 2 == 0) su++
                if (rockRidge && su < len) rockRidgeName(data, p + su, p + len)?.let { name = it }
                else if (!joliet) { name = name.substringBefore(';'); if (name.endsWith(".")) name = name.dropLast(1) }
                else name = name.substringBefore(';')
                val ext = u32le(data, p + 2); val sz = u32le(data, p + 10)
                val dir = flags and 0x02 != 0
                val mt = time(data, p + 18)
                val cur = pending
                if (cur != null && cur.name == name) {
                    val all = cur.extents + (ext to sz)
                    pending = Rec(name, cur.extent, cur.size + sz, dir, cur.mtime, all)
                } else {
                    pending?.let { out.add(it) }
                    pending = Rec(name, ext, sz, dir, mt, listOf(ext to sz))
                }
                if (flags and 0x80 == 0) { out.add(pending!!); pending = null }     // not a multi-extent continuation
            }
            p += len
        }
        pending?.let { out.add(it) }
        return out
    }

    private fun children(path: String): List<Rec>? {
        dirCache[path]?.let { return it }
        val (ext, size) = if (path == "/" || path.isEmpty()) rootExtent to rootSize else {
            val r = recCache[path] ?: resolve(path) ?: return null
            if (!r.dir) return null
            r.extent to r.size
        }
        val list = parseDir(ext, size)
        dirCache[path] = list
        val base = if (path == "/") "" else path
        for (r in list) recCache["$base/${r.name}"] = r
        return list
    }

    private fun resolve(path: String): Rec? {
        recCache[path]?.let { return it }
        var cur = ""
        for (part in path.trim('/').split('/')) {
            val list = children(if (cur.isEmpty()) "/" else cur) ?: return null
            list.firstOrNull { it.name == part } ?: return null
            cur = "$cur/$part"
        }
        return recCache[path]
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val base = if (path == "/" || path.isEmpty()) "" else path.trimEnd('/')
        val list = children(if (base.isEmpty()) "/" else base) ?: return emptyList()
        return list.map { FileSystemEntry(it.name, "$base/${it.name}", it.dir, if (it.dir) 0 else it.size, it.mtime, it.mtime) }
    }

    // ── file data ──

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        return if (readFileTo(entry, out)) out.toByteArray() else null
    }

    override fun readFileTo(entry: FileSystemEntry, out: java.io.OutputStream, onProgress: ((Long) -> Unit)?): Boolean {
        val r = resolve(entry.path) ?: return false
        var done = 0L
        for ((ext, sz) in r.extents) {
            var off = 0L
            while (off < sz) {
                val n = minOf(1L shl 20, sz - off).toInt()
                val b = readBytes(ext * LS + off, n) ?: return false
                out.write(b); off += n; done += n; onProgress?.invoke(done)
            }
        }
        return true
    }

    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int): Int {
        val r = resolve(entry.path) ?: return -1
        if (offset >= r.size) return 0
        var skip = offset; var filled = 0
        for ((ext, sz) in r.extents) {
            if (skip >= sz) { skip -= sz; continue }
            val n = minOf((sz - skip), (len - filled).toLong()).toInt()
            val b = readBytes(ext * LS + skip, n) ?: return if (filled > 0) filled else -1
            System.arraycopy(b, 0, buf, bufOff + filled, n); filled += n; skip = 0
            if (filled >= len) break
        }
        return filled
    }

    override fun unmount() { dirCache.clear(); recCache.clear() }
}
