package app.feldkit.storage.filesystem

object FilesystemDetector {

    fun detect(
        bootSector: ByteArray
    ): FilesystemType {

        if (bootSector.size < 512) {
            return FilesystemType.UNKNOWN
        }

        // NTFS
        if (
            bootSector[3] == 'N'.code.toByte() &&
            bootSector[4] == 'T'.code.toByte() &&
            bootSector[5] == 'F'.code.toByte() &&
            bootSector[6] == 'S'.code.toByte()
        ) {
            return FilesystemType.NTFS
        }

        // exFAT
        if (
            bootSector[3] == 'E'.code.toByte() &&
            bootSector[4] == 'X'.code.toByte() &&
            bootSector[5] == 'F'.code.toByte() &&
            bootSector[6] == 'A'.code.toByte() &&
            bootSector[7] == 'T'.code.toByte()
        ) {
            return FilesystemType.EXFAT
        }

        // FAT32
        if (
            bootSector[82] == 'F'.code.toByte() &&
            bootSector[83] == 'A'.code.toByte() &&
            bootSector[84] == 'T'.code.toByte() &&
            bootSector[85] == '3'.code.toByte() &&
            bootSector[86] == '2'.code.toByte()
        ) {
            return FilesystemType.FAT32
        }

        // FAT16
        if (
            bootSector[54] == 'F'.code.toByte() &&
            bootSector[55] == 'A'.code.toByte() &&
            bootSector[56] == 'T'.code.toByte() &&
            bootSector[57] == '1'.code.toByte() &&
            bootSector[58] == '6'.code.toByte()
        ) {
            return FilesystemType.FAT16
        }

        // FAT12
        if (
            bootSector[54] == 'F'.code.toByte() &&
            bootSector[55] == 'A'.code.toByte() &&
            bootSector[56] == 'T'.code.toByte() &&
            bootSector[57] == '1'.code.toByte() &&
            bootSector[58] == '2'.code.toByte()
        ) {
            return FilesystemType.FAT12
        }

        return FilesystemType.UNKNOWN
    }
}