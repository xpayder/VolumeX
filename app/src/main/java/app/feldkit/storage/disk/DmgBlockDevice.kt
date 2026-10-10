package app.feldkit.storage.disk

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater

/**
 * An Apple disk image (UDIF .dmg) presented as the disk it contains. Handles raw, zero-filled, zlib (UDZO) and bzip2 (UDBZ)
 * runs. LZFSE (ULFO / ULMO) and ADC runs are recognised and reported as unsupported.
 */
class DmgBlockDevice private constructor(private val src: ByteSource, private val runs: List<Run>, private val total: Long) : BlockDeviceReader {
    class Run(val type: Long, val firstSector: Long, val sectors: Long, val offset: Long, val length: Long)
    class Unsupported(val what: String) : Exception(what)

    private val cache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, ByteArray>?) = size > 6 }

    override fun open() = true
    override fun close() {}
    override fun isOpen() = true
    override fun sectorSize() = 512
    override fun sectorCount() = total

    private fun runIndex(sector: Long): Int {
        var lo = 0; var hi = runs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1; val r = runs[mid]
            if (sector < r.firstSector) hi = mid - 1 else if (sector >= r.firstSector + r.sectors) lo = mid + 1 else return mid
        }
        return -1
    }

    @Synchronized private fun runData(i: Int): ByteArray? {
        cache[i]?.let { return it }
        val r = runs[i]
        val outLen = (r.sectors * 512).toInt()
        val out: ByteArray = when (r.type) {
            0L, 2L -> ByteArray(outLen)
            1L -> ByteArray(outLen).also { b -> readFully(r.offset, b, outLen) }
            0x80000005L -> {
                val comp = ByteArray(r.length.toInt()); readFully(r.offset, comp, comp.size)
                val inf = Inflater(); inf.setInput(comp)
                val b = ByteArray(outLen); var n = 0
                while (n < outLen && !inf.finished()) { val k = inf.inflate(b, n, outLen - n); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += k }
                inf.end(); b
            }
            0x80000006L -> {
                val comp = ByteArray(r.length.toInt()); readFully(r.offset, comp, comp.size)
                val b = ByteArray(outLen)
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(ByteArrayInputStream(comp)).use { s -> var n = 0; while (n < outLen) { val k = s.read(b, n, outLen - n); if (k <= 0) break; n += k } }
                b
            }
            0x80000004L -> throw Unsupported("ADC compression")
            0x80000007L -> throw Unsupported("LZFSE compression")
            else -> ByteArray(outLen)
        }
        cache[i] = out
        return out
    }

    private fun readFully(off: Long, b: ByteArray, len: Int) { var got = 0; while (got < len) { val n = src.read(off + got, b, got, len - got); if (n <= 0) throw java.io.IOException("short read in image"); got += n } }

    override fun readSector(lba: Long): ByteArray? = readSectors(lba, 1)

    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        if (startLba < 0 || startLba + count > total) return null
        val out = ByteArray(count * 512); var s = startLba; var done = 0
        try {
            while (done < count) {
                val i = runIndex(s)
                if (i < 0) { s++; done++; continue }          // a gap between runs reads as zeros
                val r = runs[i]; val data = runData(i) ?: return null
                val inRun = (s - r.firstSector).toInt(); val n = minOf(count - done, (r.sectors - inRun).toInt())
                System.arraycopy(data, inRun * 512, out, done * 512, n * 512); s += n; done += n
            }
        } catch (e: Unsupported) { return null } catch (e: Exception) { return null }
        return out
    }

    companion object {
        /** Returns the device, or throws [Unsupported] / null when [src] is not a readable UDIF image. */
        fun open(src: ByteSource): DmgBlockDevice? {
            if (src.length < 1024) return null
            val kolyBuf = ByteArray(512)
            if (src.read(src.length - 512, kolyBuf, 0, 512) < 512) return null
            if (String(kolyBuf, 0, 4, Charsets.ISO_8859_1) != "koly") return null
            val k = ByteBuffer.wrap(kolyBuf).order(ByteOrder.BIG_ENDIAN)
            val xmlOff = k.getLong(216); val xmlLen = k.getLong(224)
            if (xmlOff <= 0 || xmlLen <= 0 || xmlLen > 64L shl 20) return null
            val xml = ByteArray(xmlLen.toInt()); var got = 0
            while (got < xml.size) { val n = src.read(xmlOff + got, xml, got, xml.size - got); if (n <= 0) return null; got += n }
            val text = String(xml, Charsets.UTF_8)
            val runs = ArrayList<Run>(); var total = 0L
            // every <data> element inside the blkx array is one base64 'mish' table
            val re = Regex("<data>([^<]*)</data>")
            var inBlkx = false
            val blkx = text.substringAfter("<key>blkx</key>", "").substringBefore("</array>")
            for (m in re.findAll(blkx)) {
                val raw = try { Base64.getMimeDecoder().decode(m.groupValues[1].trim()) } catch (_: Exception) { continue }
                if (raw.size < 204 || String(raw, 0, 4, Charsets.ISO_8859_1) != "mish") continue
                val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                val first = b.getLong(8); val dataOffset = b.getLong(24)
                val n = b.getInt(200)
                for (i in 0 until n) {
                    val o = 204 + i * 40; if (o + 40 > raw.size) break
                    val type = b.getInt(o).toLong() and 0xFFFFFFFFL
                    if (type == 0x7ffffffeL || type == 0xffffffffL) continue
                    val sec = b.getLong(o + 8); val cnt = b.getLong(o + 16); val off = b.getLong(o + 24); val len = b.getLong(o + 32)
                    if (cnt <= 0) continue
                    runs.add(Run(type, first + sec, cnt, dataOffset + off, len))
                    total = maxOf(total, first + sec + cnt)
                }
                inBlkx = true
            }
            if (!inBlkx || runs.isEmpty()) return null
            runs.sortBy { it.firstSector }
            val unsupported = runs.firstOrNull { it.type == 0x80000007L || it.type == 0x80000004L }
            if (unsupported != null) throw Unsupported(if (unsupported.type == 0x80000007L) "LZFSE-compressed images (ULFO / ULMO) are not supported yet" else "ADC-compressed images are not supported")
            return DmgBlockDevice(src, runs, total)
        }
    }
}
