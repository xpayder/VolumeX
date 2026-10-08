package com.fatalpuppet.volumex.storage.filesystem.hfsplus

data class HfsPlusForkData(
    val logicalSize: Long,
    val clumpSize: Int,
    val totalBlocks: Int,
    val extents: List<HfsPlusExtentDescriptor>  // up to 8
)

data class HfsPlusExtentDescriptor(
    val startBlock: Int,
    val blockCount: Int
)

data class HfsPlusVolumeHeader(
    val signature: Short,
    val version: Short,
    val attributes: Int,
    val lastMountedVersion: Int,
    val journalInfoBlock: Int,
    val createDate: Int,        // HFS+ Mac timestamps (seconds since 1904-01-01)
    val modifyDate: Int,
    val backupDate: Int,
    val checkedDate: Int,
    val fileCount: Int,
    val folderCount: Int,
    val blockSize: Int,
    val totalBlocks: Int,
    val freeBlocks: Int,
    val nextAllocation: Int,
    val rsrcClumpSize: Int,
    val dataClumpSize: Int,
    val nextCatalogID: Int,
    val writeCount: Int,
    val encodingsBitmap: Long,
    val finderInfo: IntArray,   // 8 ints
    val allocationFile: HfsPlusForkData,
    val extentsFile: HfsPlusForkData,
    val catalogFile: HfsPlusForkData,
    val attributesFile: HfsPlusForkData,
    val startupFile: HfsPlusForkData
) {
    val isHfsPlus: Boolean get() = signature == HfsPlusConstants.HFS_PLUS_SIGNATURE
    val isHfsx: Boolean get() = signature == HfsPlusConstants.HFSX_SIGNATURE
    val isJournaled: Boolean get() = (attributes and HfsPlusConstants.ATTR_VOLUME_JOURNALED) != 0
}
