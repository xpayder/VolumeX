package com.fatalpuppet.volumex.storage.filesystem.apfs

data class ApfsVolumeSuperblock(
    val header: ApfsObjectHeader,
    val fsIndex: Int,
    val features: Long,
    val readonlyCompatibleFeatures: Long,
    val incompatibleFeatures: Long,
    val omapOid: Long,          // volume's own OMAP
    val rootTreeOid: Long,      // filesystem tree (catalog) OID
    val extentrefTreeOid: Long,
    val snapMetaTreeOid: Long,
    val nextObjId: Long,
    val numFiles: Long,
    val numDirectories: Long,
    val numSymlinks: Long,
    val numOtherFsObjects: Long,
    val numSnapshots: Long,
    val volUuid: ByteArray,
    val lastModTime: Long,
    val fsFlags: Long,
    val volumeName: String
) {
    // Backward compat
    val rootDirectoryObjectId: Long get() = rootTreeOid
    val isEncrypted: Boolean get() = (incompatibleFeatures and 0x01L) != 0L
    val volUuidString: String get() = volUuid.joinToString("") { "%02X".format(it) }
        .let { h ->
            if (h.length >= 32)
                "${h.substring(0,8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20)}"
            else h
        }
}
