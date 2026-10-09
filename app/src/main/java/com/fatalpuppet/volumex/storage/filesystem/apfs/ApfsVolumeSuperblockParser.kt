package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ApfsVolumeSuperblockParser {
    private const val TAG = "VolumeX"

    fun parse(data: ByteArray): ApfsVolumeSuperblock? {
        if (data.size < 0x3C0) {
            Log.w(TAG, "Volume superblock data too small: ${data.size}")
            return null
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Object header at 0x00 (32 bytes)
        val header = ApfsObjectHeader.parse(data, 0)

        // apfs_magic at 0x20
        buf.position(0x20)
        val magic = buf.getInt()
        if (magic != ApfsConstants.APFS_MAGIC) {
            Log.w(TAG, "Invalid APSB magic: 0x${magic.toLong().and(0xFFFFFFFFL).toString(16).uppercase()}")
            return null
        }

        // apfs_fs_index at 0x24
        val fsIndex = buf.getInt()

        // apfs_features at 0x28
        val features = buf.getLong()
        // apfs_readonly_compatible_features at 0x30
        val readonlyCompatFeatures = buf.getLong()
        // apfs_incompatible_features at 0x38
        val incompatFeatures = buf.getLong()

        // apfs_unmount_time at 0x40 (skip 8)
        buf.getLong()

        // apfs_fs_reserve_block_count at 0x48 (skip 8)
        buf.getLong()
        // apfs_quota_block_count at 0x50 (skip 8)
        buf.getLong()
        // apfs_fs_alloc_count at 0x58 (skip 8)
        buf.getLong()

        // apfs_meta_crypto at 0x60 (20 bytes, skip)
        buf.position(0x74)

        // apfs_root_tree_type at 0x74
        val rootTreeType = buf.getInt()
        // apfs_extentref_tree_type at 0x78
        val extentrefTreeType = buf.getInt()
        // apfs_snap_meta_tree_type at 0x7C
        val snapMetaTreeType = buf.getInt()

        // apfs_omap_oid at 0x80
        val omapOid = buf.getLong()
        // apfs_root_tree_oid at 0x88
        val rootTreeOid = buf.getLong()
        // apfs_extentref_tree_oid at 0x90
        val extentrefTreeOid = buf.getLong()
        // apfs_snap_meta_tree_oid at 0x98
        val snapMetaTreeOid = buf.getLong()

        // apfs_revert_to_xid (0xA0) and apfs_revert_to_sblock_oid (0xA8)
        buf.position(0x58)
        val allocCount = buf.getLong()           // apfs_fs_alloc_count (blocks this volume uses)
        buf.position(0xB0)
        // apfs_next_obj_id at 0xB0
        val nextObjId = buf.getLong()
        val numFiles = buf.getLong()          // 0xB8
        val numDirs = buf.getLong()           // 0xC0
        val numSymlinks = buf.getLong()       // 0xC8
        val numOtherFsObjects = buf.getLong() // 0xD0
        val numSnapshots = buf.getLong()      // 0xD8
        // total blocks alloced 0xE0, freed 0xE8
        buf.position(0xF0)
        val volUuid = ByteArray(16)           // apfs_vol_uuid at 0xF0
        buf.get(volUuid)
        val lastModTime = buf.getLong()       // 0x100
        val fsFlags = buf.getLong()           // 0x108
        // apfs_vol_name at 0x2C0 (256 bytes, UTF-8)
        buf.position(0x2C0)
                val nameBytes = ByteArray(256)
        buf.get(nameBytes)
        val nameEnd = nameBytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: nameBytes.size
        val volumeName = String(nameBytes, 0, nameEnd, Charsets.UTF_8)

        Log.i(TAG, "APFS Volume: name='$volumeName', fsFlags=0x${fsFlags.toString(16)}")

        return ApfsVolumeSuperblock(
            header = header,
            fsIndex = fsIndex,
            features = features,
            readonlyCompatibleFeatures = readonlyCompatFeatures,
            incompatibleFeatures = incompatFeatures,
            omapOid = omapOid,
            rootTreeOid = rootTreeOid,
            extentrefTreeOid = extentrefTreeOid,
            snapMetaTreeOid = snapMetaTreeOid,
            nextObjId = nextObjId,
            numFiles = numFiles,
            numDirectories = numDirs,
            numSymlinks = numSymlinks,
            numOtherFsObjects = numOtherFsObjects,
            numSnapshots = numSnapshots,
            volUuid = volUuid,
            lastModTime = lastModTime,
            fsFlags = fsFlags,
            volumeName = volumeName,
            allocCount = allocCount
        )
    }
}
