package com.fatalpuppet.volumex.storage.filesystem.hfsplus

object HfsPlusConstants {
    // Volume header signatures
    const val HFS_PLUS_SIGNATURE: Short = 0x482B.toShort()  // 'H+'
    const val HFSX_SIGNATURE: Short = 0x4858.toShort()       // 'HX'

    // B-tree node kinds
    const val BT_LEAF_NODE: Byte = (-1).toByte()   // 0xFF
    const val BT_INDEX_NODE: Byte = 0
    const val BT_HEADER_NODE: Byte = 1
    const val BT_MAP_NODE: Byte = 2

    // Catalog record types
    const val HFS_PLUS_FOLDER_RECORD: Short = 0x0001
    const val HFS_PLUS_FILE_RECORD: Short = 0x0002
    const val HFS_PLUS_FOLDER_THREAD_RECORD: Short = 0x0003
    const val HFS_PLUS_FILE_THREAD_RECORD: Short = 0x0004

    // Well-known CNIDs
    const val ROOT_PARENT_ID: Int = 1
    const val ROOT_FOLDER_ID: Int = 2
    const val EXTENTS_FILE_ID: Int = 3
    const val CATALOG_FILE_ID: Int = 4
    const val BAD_BLOCK_FILE_ID: Int = 5
    const val ALLOCATION_FILE_ID: Int = 6
    const val STARTUP_FILE_ID: Int = 7
    const val ATTRIBUTES_FILE_ID: Int = 8
    const val FIRST_USER_CNID: Int = 16

    // Volume attributes
    const val ATTR_NO_CACHE_PAGER: Int = 0x00000001
    const val ATTR_UNMOUNTED_CLEANLY: Int = 0x00000100
    const val ATTR_BADBLOCKS_EXIST: Int = 0x00000200
    const val ATTR_VOLUME_JOURNALED: Int = 0x00002000
    const val ATTR_SOFTWARE_LOCK: Int = 0x00008000

    // HFS+ volume header size (not counting object header)
    const val VOLUME_HEADER_SIZE: Int = 512
    // Volume header offset from partition start
    const val VOLUME_HEADER_OFFSET: Int = 1024
}
