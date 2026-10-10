package app.feldkit.storage.filesystem.hfsplus

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object HfsPlusVolumeHeaderParser {
    private const val TAG = "FeldKit"

    fun parse(data: ByteArray): HfsPlusVolumeHeader? {
        // Volume header is at byte 1024 from partition start, but caller passes the 512-byte block
        // containing it. If data >= 512, parse from offset 0.
        if (data.size < 512) {
            Log.w(TAG, "HFS+ header data too small: ${data.size}")
            return null
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN) // HFS+ is big-endian

        val signature = buf.getShort()
        if (signature != HfsPlusConstants.HFS_PLUS_SIGNATURE && signature != HfsPlusConstants.HFSX_SIGNATURE) {
            Log.w(TAG, "Invalid HFS+ signature: 0x${(signature.toInt() and 0xFFFF).toString(16).uppercase()}")
            return null
        }

        val version = buf.getShort()
        val attributes = buf.getInt()
        val lastMountedVersion = buf.getInt()
        val journalInfoBlock = buf.getInt()
        val createDate = buf.getInt()
        val modifyDate = buf.getInt()
        val backupDate = buf.getInt()
        val checkedDate = buf.getInt()
        val fileCount = buf.getInt()
        val folderCount = buf.getInt()
        val blockSize = buf.getInt()
        val totalBlocks = buf.getInt()
        val freeBlocks = buf.getInt()
        val nextAllocation = buf.getInt()
        val rsrcClumpSize = buf.getInt()
        val dataClumpSize = buf.getInt()
        val nextCatalogID = buf.getInt()
        val writeCount = buf.getInt()
        val encodingsBitmap = buf.getLong()

        val finderInfo = IntArray(8) { buf.getInt() }

        val allocationFile = parseForkData(buf)
        val extentsFile = parseForkData(buf)
        val catalogFile = parseForkData(buf)
        val attributesFile = parseForkData(buf)
        val startupFile = parseForkData(buf)

        Log.i(TAG, "HFS+ Volume: blockSize=$blockSize, totalBlocks=$totalBlocks, files=$fileCount, folders=$folderCount")
        Log.i(TAG, "HFS+ Catalog: firstExtent startBlock=${catalogFile.extents.firstOrNull()?.startBlock}, blockCount=${catalogFile.extents.firstOrNull()?.blockCount}")

        return HfsPlusVolumeHeader(
            signature, version, attributes, lastMountedVersion, journalInfoBlock,
            createDate, modifyDate, backupDate, checkedDate,
            fileCount, folderCount, blockSize, totalBlocks, freeBlocks,
            nextAllocation, rsrcClumpSize, dataClumpSize, nextCatalogID, writeCount,
            encodingsBitmap, finderInfo,
            allocationFile, extentsFile, catalogFile, attributesFile, startupFile
        )
    }

    private fun parseForkData(buf: ByteBuffer): HfsPlusForkData {
        val logicalSize = buf.getLong()
        val clumpSize = buf.getInt()
        val totalBlocks = buf.getInt()
        val extents = (0 until 8).map {
            HfsPlusExtentDescriptor(buf.getInt(), buf.getInt())
        }
        return HfsPlusForkData(logicalSize, clumpSize, totalBlocks, extents)
    }
}
