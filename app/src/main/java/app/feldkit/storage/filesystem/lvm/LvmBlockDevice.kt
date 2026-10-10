package app.feldkit.storage.filesystem.lvm

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader

/**
 * Virtual block device that maps an LVM logical volume's logical extents
 * to physical extents on the underlying PV block device.
 *
 * Only linear segments (type = "striped" with one PV stripe) are supported.
 */
class LvmBlockDevice(
    private val pv: BlockDeviceReader,
    private val lv: LvmLogicalVolume,
    private val extentSizeSectors: Long,  // extent_size from VG metadata (in 512-byte sectors)
    /** Where the PV's data area (first extent) starts, in sectors: pe_start from the PV header. */
    private val dataOffsetSectors: Long = 0L
) : BlockDeviceReader {

    companion object {
        private const val TAG = "FeldKit"
    }

    private val totalExtents: Long = lv.segments.sumOf { it.extentCount }

    override fun open(): Boolean = pv.open()
    override fun close() = pv.close()
    override fun isOpen(): Boolean = pv.isOpen()

    override fun sectorSize(): Int = pv.sectorSize()

    override fun sectorCount(): Long = totalExtents * extentSizeSectors

    override fun readSector(lba: Long): ByteArray? {
        val (pvLba, _) = mapLba(lba) ?: return null
        return pv.readSector(pvLba)
    }

    /** Reads follow the extent map in whole runs instead of one sector at a time. */
    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        val out = ByteArray(count * sectorSize()); var done = 0
        while (done < count) {
            val lba = startLba + done
            val (pvLba, _) = mapLba(lba) ?: return null
            val inExtent = (extentSizeSectors - lba % extentSizeSectors).toInt()
            val n = minOf(count - done, inExtent)
            val b = pv.readSectors(pvLba, n) ?: return null
            System.arraycopy(b, 0, out, done * sectorSize(), b.size); done += n
        }
        return out
    }

    override fun writeSector(lba: Long, data: ByteArray): Boolean {
        val (pvLba, _) = mapLba(lba) ?: return false
        return pv.writeSector(pvLba, data)
    }

    override fun flushCache(): Boolean = pv.flushCache()

    /**
     * Maps a logical LBA to a physical LBA on the PV.
     * Returns (pvLba, segmentName) or null if out of range.
     */
    private fun mapLba(lba: Long): Pair<Long, String>? {
        val logicalExtent = lba / extentSizeSectors
        val offsetInExtent = lba % extentSizeSectors

        for (seg in lv.segments) {
            if (logicalExtent >= seg.startExtent && logicalExtent < seg.startExtent + seg.extentCount) {
                val extentOffset = logicalExtent - seg.startExtent
                val pvLba = dataOffsetSectors + (seg.pvStartExtent + extentOffset) * extentSizeSectors + offsetInExtent
                return Pair(pvLba, seg.pvName)
            }
        }
        Log.w(TAG, "LVM: lba=$lba out of range (totalExtents=$totalExtents, extentSize=$extentSizeSectors)")
        return null
    }
}
