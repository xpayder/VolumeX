package com.fatalpuppet.volumex.storage.filesystem.ext

object ExtConstants {
    const val EXT_MAGIC: Int = 0xEF53

    // Superblock offsets (within the 1024-byte superblock block)
    const val SB_INODES_COUNT: Int = 0        // uint32
    const val SB_BLOCKS_COUNT_LO: Int = 4     // uint32
    const val SB_FREE_BLOCKS_LO: Int = 12     // uint32
    const val SB_FREE_INODES: Int = 16        // uint32
    const val SB_FIRST_DATA_BLOCK: Int = 20   // uint32
    const val SB_LOG_BLOCK_SIZE: Int = 24     // uint32 → block_size = 1024 << value
    const val SB_BLOCKS_PER_GROUP: Int = 32   // uint32
    const val SB_INODES_PER_GROUP: Int = 40   // uint32
    const val SB_MOUNT_TIME: Int = 44         // uint32
    const val SB_WRITE_TIME: Int = 48         // uint32
    const val SB_MAGIC: Int = 56             // uint16
    const val SB_INODE_SIZE: Int = 88         // uint16
    const val SB_BLOCK_GROUP_NR: Int = 90     // uint16
    const val SB_FEATURE_COMPAT: Int = 92     // uint32
    const val SB_FEATURE_INCOMPAT: Int = 96   // uint32
    const val SB_FEATURE_RO_COMPAT: Int = 100 // uint32
    const val SB_UUID: Int = 104             // 16 bytes
    const val SB_VOLUME_NAME: Int = 120      // 16 bytes
    const val SB_DESC_SIZE: Int = 264        // uint16 — ext4 flex descriptor size

    // Incompatible feature flags
    const val INCOMPAT_FILETYPE: Int  = 0x00000002
    const val INCOMPAT_EXTENTS: Int   = 0x00000040
    const val INCOMPAT_64BIT: Int     = 0x00000080
    const val INCOMPAT_FLEX_BG: Int   = 0x00000200
    const val INCOMPAT_INLINE_DATA: Int = 0x00008000

    // Inode flags
    const val EXT4_EXTENTS_FL: Int = 0x00080000

    // Block group descriptor offsets (32-byte ext2/3, 64-byte ext4 64-bit)
    const val BGD_BLOCK_BITMAP_LO: Int = 0   // uint32
    const val BGD_INODE_BITMAP_LO: Int = 4   // uint32
    const val BGD_INODE_TABLE_LO: Int  = 8   // uint32
    const val BGD_FREE_BLOCKS_LO: Int  = 12  // uint16
    const val BGD_FREE_INODES_LO: Int  = 14  // uint16
    const val BGD_USED_DIRS_LO: Int    = 16  // uint16
    const val BGD_FLAGS: Int           = 18  // uint16

    // Inode structure offsets
    const val INODE_MODE: Int        = 0   // uint16
    const val INODE_UID_LO: Int      = 2   // uint16
    const val INODE_SIZE_LO: Int     = 4   // uint32
    const val INODE_ATIME: Int       = 8   // uint32
    const val INODE_CTIME: Int       = 12  // uint32
    const val INODE_MTIME: Int       = 16  // uint32
    const val INODE_DTIME: Int       = 20  // uint32
    const val INODE_LINKS_COUNT: Int = 26  // uint16
    const val INODE_BLOCKS_LO: Int   = 28  // uint32 (in 512-byte units)
    const val INODE_FLAGS: Int       = 32  // uint32
    const val INODE_BLOCK: Int       = 40  // 60 bytes (block pointers OR extent tree)
    const val INODE_SIZE_HIGH: Int   = 108 // uint32 (ext4)

    // Extent header (at inode_block[0] when EXT4_EXTENTS_FL is set)
    const val EXT4_EXT_MAGIC: Int = 0xF30A
    const val EXTENTS_HEADER_MAGIC: Int = 0   // uint16
    const val EXTENTS_HEADER_ENTRIES: Int = 2 // uint16
    const val EXTENTS_HEADER_MAX: Int = 4     // uint16
    const val EXTENTS_HEADER_DEPTH: Int = 6   // uint16

    // Extent leaf entry offsets (12 bytes each)
    const val EXTENT_BLOCK: Int     = 0  // uint32 — first logical block
    const val EXTENT_LEN: Int       = 4  // uint16 — number of blocks
    const val EXTENT_START_HI: Int  = 6  // uint16
    const val EXTENT_START_LO: Int  = 8  // uint32

    // Extent index entry offsets (12 bytes each)
    const val EXT_IDX_BLOCK: Int    = 0  // uint32
    const val EXT_IDX_LEAF_LO: Int  = 4  // uint32
    const val EXT_IDX_LEAF_HI: Int  = 8  // uint16

    // Direct block pointer count
    const val EXT2_NDIR_BLOCKS: Int = 12
    const val EXT2_IND_BLOCK: Int   = 12
    const val EXT2_DIND_BLOCK: Int  = 13
    const val EXT2_TIND_BLOCK: Int  = 14

    // Mode bits
    const val S_IFMT: Int   = 0xF000
    const val S_IFREG: Int  = 0x8000
    const val S_IFDIR: Int  = 0x4000
    const val S_IFLNK: Int  = 0xA000

    // Root inode number
    const val EXT2_ROOT_INO: Int = 2

    // Directory record offsets
    const val DIRENT_INODE: Int    = 0  // uint32
    const val DIRENT_REC_LEN: Int  = 4  // uint16
    const val DIRENT_NAME_LEN: Int = 6  // uint8
    const val DIRENT_FILE_TYPE: Int = 7 // uint8 (ext2 v2+ / ext3 / ext4)
    const val DIRENT_NAME: Int     = 8  // variable
}
