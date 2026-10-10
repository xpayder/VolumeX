package app.feldkit.storage.filesystem.lvm

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LVM2 Physical Volume parser.
 *
 * On-disk layout:
 *   Sector 0: MBR / empty
 *   Sector 1 (offset 512): PV label "LABELONE\0\0\0\0\0\0\0\0" (first 8 bytes)
 *     label_sector (8)     = 1
 *     label_crc   (4)
 *     offset      (4)      = offset to pv_header from start of this sector
 *     type_ind    (8)      = "LVM2 001"
 *   pv_header (at offset above):
 *     pv_uuid[32]
 *     device_size (8)      = PV size in bytes
 *     disk_areas: data_areas (2×16 each), metadata_areas (2×16 each)
 *       da_offset (8), da_size (8)
 *   Metadata area:
 *     mda_header: magic, version, start, size, checksum, raw_loc×2
 *     rlocn (metadata text) at mda_header.raw_loc.offset
 *
 * The metadata text is a configuration-file-like format describing VG layout.
 */
data class LvmPhysicalVolume(
    val uuid: String,
    val deviceSizeBytes: Long,
    val metadataOffset: Long,
    val metadataSize: Long
)

data class LvmLogicalVolume(
    val name: String,
    val uuid: String,
    val segmentCount: Int,
    val segments: List<LvmSegment>
)

data class LvmSegment(
    val startExtent: Long,
    val extentCount: Long,
    val pvName: String,
    val pvStartExtent: Long
)

object LvmParser {
    private const val TAG = "FeldKit"
    private val LABEL_MAGIC = "LABELONE".toByteArray(Charsets.US_ASCII)
    private val LVM2_TYPE = "LVM2 001".toByteArray(Charsets.US_ASCII)
    private val MDA_MAGIC = byteArrayOf(
        0x20, 0x4C, 0x56, 0x4D, 0x32, 0x20, 0x78, 0x5B,
        0x35, 0x41, 0x25, 0x72, 0x30, 0x4E, 0x2A, 0x3E
    )

    fun detect(blockDevice: BlockDeviceReader): Boolean {
        val sector1 = blockDevice.readSector(1) ?: return false
        return sector1.sliceArray(0..7).contentEquals(LABEL_MAGIC)
    }

    fun parsePV(blockDevice: BlockDeviceReader): LvmPhysicalVolume? {
        val sector1 = blockDevice.readSector(1) ?: return null
        if (!sector1.sliceArray(0..7).contentEquals(LABEL_MAGIC)) return null

        val buf = ByteBuffer.wrap(sector1).order(ByteOrder.LITTLE_ENDIAN)
        val pvhOffset = buf.getInt(20).toLong()

        // Combine sectors 1 and 2 to read pv_header safely
        val sector2 = blockDevice.readSector(2) ?: ByteArray(512)
        val pvData = sector1 + sector2

        val pvBuf = ByteBuffer.wrap(pvData, pvhOffset.toInt(), pvData.size - pvhOffset.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)

        val uuidBytes = ByteArray(32)
        pvBuf.get(uuidBytes)
        val uuid = String(uuidBytes, Charsets.US_ASCII).replace("-", "").trimEnd('\u0000')
        val deviceSize = pvBuf.getLong()

        // data area [0] offset and size
        val da0off = pvBuf.getLong()
        val da0size = pvBuf.getLong()
        // data area [1] (terminator, both 0)
        pvBuf.getLong(); pvBuf.getLong()
        // metadata area [0]
        val mda0off = pvBuf.getLong()
        val mda0size = pvBuf.getLong()

        Log.d(TAG, "LVM PV: uuid=$uuid deviceSize=$deviceSize mda0off=$mda0off mda0size=$mda0size")
        return LvmPhysicalVolume(uuid, deviceSize, mda0off, mda0size)
    }

    fun readVgMetadata(blockDevice: BlockDeviceReader, pv: LvmPhysicalVolume): String? {
        // mda_header is at pv.metadataOffset
        val mdaSector = pv.metadataOffset / 512
        val mdaOff = (pv.metadataOffset % 512).toInt()
        val mdaSector0 = blockDevice.readSector(mdaSector) ?: return null
        val mdaSector1 = blockDevice.readSector(mdaSector + 1) ?: ByteArray(512)
        val mdaData = mdaSector0 + mdaSector1

        if (mdaOff + 512 > mdaData.size) return null
        val magic = mdaData.sliceArray(mdaOff until mdaOff + 16)
        if (!magic.contentEquals(MDA_MAGIC)) {
            Log.w(TAG, "LVM: MDA magic mismatch")
            return null
        }

        val buf = ByteBuffer.wrap(mdaData, mdaOff + 16, 40).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.getInt()
        val mdaStart = buf.getLong()
        val mdaSize = buf.getLong()
        // raw_locn[0]
        val rlocnOffset = buf.getLong()
        val rlocnSize = buf.getLong()

        // metadata text at pv.metadataOffset + rlocnOffset
        val textOffset = pv.metadataOffset + rlocnOffset
        val textSector = textOffset / 512
        val textOff = (textOffset % 512).toInt()
        val textLen = rlocnSize.toInt().coerceIn(0, 65536)

        val textBytes = ByteArray(textLen)
        var pos = 0
        for (i in 0..(textLen / 512 + 1)) {
            val s = blockDevice.readSector(textSector + i) ?: break
            val copyFrom = if (i == 0) textOff else 0
            val copyLen = (s.size - copyFrom).coerceAtMost(textLen - pos)
            if (copyLen <= 0) break
            s.copyInto(textBytes, pos, copyFrom, copyFrom + copyLen)
            pos += copyLen
            if (pos >= textLen) break
        }
        return String(textBytes, Charsets.UTF_8)
    }

    fun parseLogicalVolumes(vgMetadata: String): List<LvmLogicalVolume> {
        val lvs = mutableListOf<LvmLogicalVolume>()

        // Find "logical_volumes {" block
        val lvBlockRe = Regex("""logical_volumes\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)
        val lvBlock = lvBlockRe.find(vgMetadata)?.groupValues?.get(1) ?: return lvs

        // Find each LV entry
        val lvRe = Regex("""(\w+)\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)
        lvRe.findAll(lvBlock).forEach { lvMatch ->
            val lvName = lvMatch.groupValues[1]
            val lvBody = lvMatch.groupValues[2]
            val uuid = extractMdaStr(lvBody, "id") ?: ""
            val segCount = extractMdaInt(lvBody, "segment_count") ?: 0
            val segments = parseSegments(lvBody)
            lvs.add(LvmLogicalVolume(lvName, uuid, segCount, segments))
        }
        return lvs
    }

    fun parseExtentSize(vgMetadata: String): Long {
        return extractMdaLong(vgMetadata, "extent_size") ?: 8192L  // default 4 MiB in 512-byte sectors = 8192
    }

    private fun parseSegments(lvBody: String): List<LvmSegment> {
        val segs = mutableListOf<LvmSegment>()
        val segRe = Regex("""segment\d+\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        segRe.findAll(lvBody).forEach { sm ->
            val sb = sm.groupValues[1]
            val startExtent = extractMdaLong(sb, "start_extent") ?: 0L
            val extentCount = extractMdaLong(sb, "extent_count") ?: 0L
            // stripes = ["pvname", pvStartExtent]
            val stripesRe = Regex(""""([^"]+)"\s*,\s*(\d+)""")
            val stripeMatch = stripesRe.find(sb)
            val pvName = stripeMatch?.groupValues?.get(1) ?: ""
            val pvStart = stripeMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L
            segs.add(LvmSegment(startExtent, extentCount, pvName, pvStart))
        }
        return segs
    }

    private fun extractMdaStr(text: String, key: String): String? {
        val r = Regex(""""?$key"?\s*=\s*"([^"]+)"""")
        return r.find(text)?.groupValues?.get(1)
    }

    private fun extractMdaInt(text: String, key: String): Int? {
        val r = Regex(""""?$key"?\s*=\s*(\d+)""")
        return r.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractMdaLong(text: String, key: String): Long? {
        val r = Regex(""""?$key"?\s*=\s*(\d+)""")
        return r.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }
}
