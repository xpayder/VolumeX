package app.feldkit.storage.filesystem.apfs

data class ApfsContainerSuperblock(
    val header: ApfsObjectHeader,
    val blockSize: Long,
    val blockCount: Long,
    val containerUuid: ByteArray,
    val nextOid: Long,
    val nextXid: Long,
    val xpDescBase: Long,      // checkpoint descriptor area base LBA
    val xpDataBase: Long,
    val xpDescBlocks: Int,
    val xpDataBlocks: Int,
    val xpDescNext: Int,
    val xpDataNext: Int,
    val xpDescIndex: Int,
    val xpDescLen: Int,
    val xpDataIndex: Int,
    val xpDataLen: Int,
    val spaceman_oid: Long,
    val omapOid: Long,         // OID of container object map
    val reaperOid: Long,
    val maxFileSystems: Int,
    val fsOids: LongArray,     // OIDs of volume superblocks
    val keylockerAddr: Long = 0L   // nx_keylocker.pr_start_paddr (FileVault key bag)
) {
    // Keep backward-compat fields used by old code
    val volumeCount: Int get() = fsOids.count { it != 0L }
    // UUID as hex string for display
    val containerUuidString: String get() = containerUuid.joinToString("") { "%02X".format(it) }
        .let { h ->
            "${h.substring(0,8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20)}"
        }
}
