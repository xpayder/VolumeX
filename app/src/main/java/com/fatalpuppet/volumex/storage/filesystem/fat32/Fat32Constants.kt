package com.fatalpuppet.volumex.storage.filesystem.fat32

object Fat32Constants {
    // Boot sector extended boot signature values
    const val EXTENDED_BOOT_SIG_1: Int = 0x28
    const val EXTENDED_BOOT_SIG_2: Int = 0x29

    // FAT32 end-of-chain value threshold
    const val FAT32_EOC_MIN: Long = 0x0FFFFFF8L
    const val FAT32_EOC_MAX: Long = 0x0FFFFFFFL
    const val FAT32_BAD_CLUSTER: Long = 0x0FFFFFF7L

    // Free cluster marker in FAT
    const val FAT32_FREE_CLUSTER: Long = 0L

    // Directory entry sizes
    const val DIR_ENTRY_SIZE: Int = 32

    // Attribute byte flags
    const val ATTR_READ_ONLY: Int = 0x01
    const val ATTR_HIDDEN: Int = 0x02
    const val ATTR_SYSTEM: Int = 0x04
    const val ATTR_VOLUME_ID: Int = 0x08
    const val ATTR_DIRECTORY: Int = 0x10
    const val ATTR_ARCHIVE: Int = 0x20
    const val ATTR_LONG_NAME: Int = ATTR_READ_ONLY or ATTR_HIDDEN or ATTR_SYSTEM or ATTR_VOLUME_ID

    // LFN (Long File Name) sequence number mask
    const val LFN_LAST: Int = 0x40
    const val LFN_SEQ_MASK: Int = 0x3F

    // Standard short-name entry states
    const val ENTRY_FREE: Byte = 0x00.toByte()
    const val ENTRY_DELETED: Byte = 0xE5.toByte()
    const val ENTRY_DOT: Byte = 0x2E.toByte()
}
