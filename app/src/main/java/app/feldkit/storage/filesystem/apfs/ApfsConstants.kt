package app.feldkit.storage.filesystem.apfs

object ApfsConstants {
    // NX Container Superblock magic: "NXSB" in little-endian = 0x4253584E
    const val NX_MAGIC: Int = 0x4253584E.toInt()

    // Volume Superblock magic: "APSB" in little-endian = 0x42535041
    const val APFS_MAGIC: Int = 0x42535041.toInt()

    // B-Tree node magic: "BTRN" in little-endian = 0x4E525442
    const val BTREE_NODE_MAGIC: Int = 0x4E525442.toInt()

    // Object type masks
    const val OBJECT_TYPE_MASK: Int = 0x0000FFFF.toInt()
    const val OBJECT_TYPE_FLAGS_MASK: Int = 0xFFFF0000.toInt()

    // Object types
    const val OBJECT_TYPE_NX_SUPERBLOCK: Int = 0x00000001
    const val OBJECT_TYPE_BTREE: Int = 0x00000002
    const val OBJECT_TYPE_BTREE_NODE: Int = 0x00000004
    const val OBJECT_TYPE_SPACEMAN: Int = 0x00000005
    const val OBJECT_TYPE_OMAP: Int = 0x0000000B
    const val OBJECT_TYPE_FS: Int = 0x0000000D

    // B-Tree node flags
    const val BTNODE_ROOT: Int = 0x0001
    const val BTNODE_LEAF: Int = 0x0002
    const val BTNODE_FIXED_KV_SIZE: Int = 0x0004
    const val BTNODE_HASHED: Int = 0x0008
    const val BTNODE_NOHEADER: Int = 0x0010

    // Filesystem record types (high 4 bits of key id_and_type)
    const val APFS_TYPE_SNAP_METADATA: Int = 1
    const val APFS_TYPE_EXTENT: Int = 2
    const val APFS_TYPE_INODE: Int = 3
    const val APFS_TYPE_XATTR: Int = 4
    const val APFS_TYPE_SIBLING_LINK: Int = 5
    const val APFS_TYPE_DSTREAM_ID: Int = 6
    const val APFS_TYPE_CRYPTO_STATE: Int = 7
    const val APFS_TYPE_FILE_EXTENT: Int = 8
    const val APFS_TYPE_DIR_REC: Int = 9
    const val APFS_TYPE_DIR_STATS: Int = 10
    const val APFS_TYPE_SNAP_NAME: Int = 11
    const val APFS_TYPE_SIBLING_MAP: Int = 12

    // Inode mode bits
    const val S_IFMT: Int = 0xF000
    const val S_IFIFO: Int = 0x1000
    const val S_IFCHR: Int = 0x2000
    const val S_IFDIR: Int = 0x4000
    const val S_IFBLK: Int = 0x6000
    const val S_IFREG: Int = 0x8000
    const val S_IFLNK: Int = 0xA000
    const val S_IFSOCK: Int = 0xC000

    // Well-known inode numbers
    const val INVALID_OBJ_ID: Long = 0x0L
    const val ROOT_DIR_PARENT: Long = 1L
    const val ROOT_DIR_INO_NUM: Long = 2L
    const val PRIV_DIR_INO_NUM: Long = 3L
    const val SNAP_DIR_INO_NUM: Long = 6L
    const val MIN_USER_INO_NUM: Long = 16L

    // Incompatible features
    const val APFS_INCOMPAT_DATALESS_SNAPS: Long = 0x00000001L
    const val APFS_INCOMPAT_ENC_ROLLED: Long = 0x00000002L
    const val APFS_INCOMPAT_NORMALIZATION_INSENSITIVE: Long = 0x00000004L
    const val APFS_INCOMPAT_INCOMPLETE_RESTORE: Long = 0x00000008L
    const val APFS_INCOMPAT_SEALED_VOLUME: Long = 0x00000010L
}
