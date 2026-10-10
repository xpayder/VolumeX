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
    private val extentSizeSectors: Long  // extent_size from VG metadata (in 512-byte sectors)
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
                val pvLba = (seg.pvStartExtent + extentOffset) * extentSizeSectors + offsetInExtent
                return Pair(pvLba, seg.pvName)
            }
        }
        Log.w(TAG, "LVM: lba=$lba out of range (totalExtents=$totalExtents, extentSize=$extentSizeSectors)")
        return null
    }
}
