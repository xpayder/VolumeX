package com.fatalpuppet.volumex.storage.filesystem.apfs

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ApfsContainerSuperblockParser {
    private const val TAG = "VolumeX"

    fun parse(data: ByteArray): ApfsContainerSuperblock? {
        if (data.size < 0x200) {
            Log.w(TAG, "Container superblock data too small: ${data.size}")
            return null
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Object header (32 bytes at offset 0x00)
        val header = ApfsObjectHeader.parse(data, 0)

        // nx_magic at 0x20
        buf.position(0x20)
        val magic = buf.getInt()
        if (magic != ApfsConstants.NX_MAGIC) {
            Log.w(TAG, "Invalid NX magic: 0x${magic.toLong().and(0xFFFFFFFFL).toString(16).uppercase()}, expected 0x${ApfsConstants.NX_MAGIC.toLong().and(0xFFFFFFFFL).toString(16).uppercase()}")
            return null
        }

        // nx_block_size at 0x24
        val blockSize = buf.getInt().toLong().and(0xFFFFFFFFL)

        // nx_block_count at 0x28
        val blockCount = buf.getLong()

        // Skip nx_features (0x30), nx_readonly_compatible_features (0x38), nx_incompatible_features (0x40)
        // nx_uuid at 0x48 (16 bytes)
        buf.position(0x48)
        val uuid = ByteArray(16)
        buf.get(uuid)

        // nx_next_oid at 0x58
        val nextOid = buf.getLong()
        // nx_next_xid at 0x60
        val nextXid = buf.getLong()

        // nx_xp_desc_blocks at 0x68
        val xpDescBlocks = buf.getInt()
        // nx_xp_data_blocks at 0x6C
        val xpDataBlocks = buf.getInt()

        // nx_xp_desc_base at 0x70
        val xpDescBase = buf.getLong()
        // nx_xp_data_base at 0x78
        val xpDataBase = buf.getLong()

        // nx_xp_desc_next at 0x80
        val xpDescNext = buf.getInt()
        // nx_xp_data_next at 0x84
        val xpDataNext = buf.getInt()
        // nx_xp_desc_index at 0x88
        val xpDescIndex = buf.getInt()
        // nx_xp_desc_len at 0x8C
        val xpDescLen = buf.getInt()
        // nx_xp_data_index at 0x90
        val xpDataIndex = buf.getInt()
        // nx_xp_data_len at 0x94
        val xpDataLen = buf.getInt()

        // nx_spaceman_oid at 0x98
        val spacemanOid = buf.getLong()
        // nx_omap_oid at 0xA0
        val omapOid = buf.getLong()
        // nx_reaper_oid at 0xA8
        val reaperOid = buf.getLong()

        // nx_test_type at 0xB0 (skip)
        buf.getInt()
        // nx_max_file_systems at 0xB4
        val maxFileSystems = buf.getInt()

        // nx_fs_oid at 0xB8, array of up to 100 uint64
        val fsOids = LongArray(maxOf(0, minOf(maxFileSystems, 100))) { buf.getLong() }

        Log.i(TAG, "APFS Container: magic OK, blockSize=$blockSize, blockCount=$blockCount, volumes=${fsOids.count { it != 0L }}")

        return ApfsContainerSuperblock(
            header = header,
            blockSize = blockSize,
            blockCount = blockCount,
            containerUuid = uuid,
            nextOid = nextOid,
            nextXid = nextXid,
            xpDescBase = xpDescBase,
            xpDataBase = xpDataBase,
            xpDescBlocks = xpDescBlocks,
            xpDataBlocks = xpDataBlocks,
            xpDescNext = xpDescNext,
            xpDataNext = xpDataNext,
            xpDescIndex = xpDescIndex,
            xpDescLen = xpDescLen,
            xpDataIndex = xpDataIndex,
            xpDataLen = xpDataLen,
            spaceman_oid = spacemanOid,
            omapOid = omapOid,
            reaperOid = reaperOid,
            maxFileSystems = maxFileSystems,
            fsOids = fsOids,
            keylockerAddr = if (data.size >= 1312) buf.getLong(com.fatalpuppet.volumex.storage.crypto.ApfsCrypto.KEYLOCKER_OFFSET) else 0L
        )
    }
}
