package com.fatalpuppet.volumex.storage.filesystem.hfsplus

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemEntry
import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo

class HfsPlusReader(
    private val reader: BlockDeviceReader,
    private val partitionStartLba: Long
) : FileSystemReader {
    companion object {
        private const val TAG = "VolumeX"
        // HFS+ volume header is at byte offset 1024 from partition start
        private const val VOLUME_HEADER_SECTOR_OFFSET = 2  // sector 2 = byte 1024 for 512-byte sectors
    }

    private var volumeHeader: HfsPlusVolumeHeader? = null
    private var btreeParser: HfsPlusBTreeParser? = null
    private var allEntries: List<HfsCatalogEntry> = emptyList()

    override fun mount(): Boolean {
        // Read the 512-byte sector that contains the volume header (at byte 1024 = sector 2)
        val sector2 = reader.readSector(partitionStartLba + VOLUME_HEADER_SECTOR_OFFSET) ?: run {
            Log.e(TAG, "Failed to read HFS+ volume header sector")
            return false
        }

        val vh = HfsPlusVolumeHeaderParser.parse(sector2) ?: run {
            Log.e(TAG, "Not a valid HFS+ volume")
            return false
        }

        volumeHeader = vh
        btreeParser = HfsPlusBTreeParser(reader, partitionStartLba, vh)

        Log.i(TAG, "HFS+ mounted: ${vh.fileCount} files, ${vh.folderCount} folders, blockSize=${vh.blockSize}")
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val vh = volumeHeader ?: return emptyList()
        return listOf(VolumeInfo(
            name = "HFS+ Volume",
            type = if (vh.isHfsx) "HFSX" else "HFS+",
            uuid = "",
            totalBlocks = vh.totalBlocks.toLong(),
            blockSize = vh.blockSize.toLong(),
            freeBlocks = vh.freeBlocks.toLong(),
            isEncrypted = false,
            numFiles = vh.fileCount.toLong(),
            numDirectories = vh.folderCount.toLong()
        ))
    }

    override fun listDirectory(volumeIndex: Int, path: String): List<FileSystemEntry> {
        val vh = volumeHeader ?: return emptyList()
        val parser = btreeParser ?: return emptyList()

        // Lazily load all entries
        if (allEntries.isEmpty()) {
            allEntries = parser.scanAllEntries()
        }

        val parentId = resolvePathId(path)

        return allEntries
            .filter { it.parentId == parentId }
            .filter { it.recordType == HfsPlusConstants.HFS_PLUS_FILE_RECORD || it.recordType == HfsPlusConstants.HFS_PLUS_FOLDER_RECORD }
            .map { entry ->
                FileSystemEntry(
                    name = entry.name,
                    path = if (path == "/" || path.isEmpty()) "/${entry.name}" else "$path/${entry.name}",
                    isDirectory = entry.isDirectory,
                    size = entry.fileSize,
                    createdAt = hfsTimestampToMs(entry.createDate),
                    modifiedAt = hfsTimestampToMs(entry.modifyDate),
                    hfsCatalogId = entry.catalogId,
                    hfsParentId = entry.parentId
                )
            }
    }

    private fun resolvePathId(path: String): Int {
        if (path == "/" || path.isEmpty()) return HfsPlusConstants.ROOT_FOLDER_ID
        val parts = path.trim('/').split("/")
        var currentId = HfsPlusConstants.ROOT_FOLDER_ID
        for (part in parts) {
            val child = allEntries.find { it.parentId == currentId && it.name == part } ?: return HfsPlusConstants.ROOT_FOLDER_ID
            currentId = child.catalogId
        }
        return currentId
    }

    // HFS+ timestamps: seconds since 1904-01-01. Convert to Unix epoch (1970-01-01).
    private fun hfsTimestampToMs(hfsTime: Int): Long {
        val HFS_EPOCH_OFFSET = 2082844800L // seconds between 1904-01-01 and 1970-01-01
        val unixSeconds = hfsTime.toLong().and(0xFFFFFFFFL) - HFS_EPOCH_OFFSET
        return unixSeconds * 1000L
    }

    override fun readFile(entry: FileSystemEntry): ByteArray? {
        val parser = btreeParser ?: return null
        // Find the catalog entry for this file
        val catalogEntry = allEntries.find {
            it.catalogId == entry.hfsCatalogId && !it.isDirectory
        } ?: return null

        val fork = catalogEntry.dataFork ?: return ByteArray(0)
        return parser.readFileFork(fork)
    }

    override fun searchFiles(query: String, volumeIndex: Int): List<FileSystemEntry> {
        if (allEntries.isEmpty()) {
            val parser = btreeParser ?: return emptyList()
            allEntries = parser.scanAllEntries()
        }
        val lq = query.lowercase()
        return allEntries
            .filter { it.name.lowercase().contains(lq) && !it.isDirectory }
            .map { entry ->
                FileSystemEntry(
                    name = entry.name,
                    path = "/${entry.name}",
                    isDirectory = entry.isDirectory,
                    size = entry.fileSize,
                    createdAt = hfsTimestampToMs(entry.createDate),
                    modifiedAt = hfsTimestampToMs(entry.modifyDate),
                    hfsCatalogId = entry.catalogId,
                    hfsParentId = entry.parentId
                )
            }
    }

    override fun unmount() {
        volumeHeader = null
        btreeParser = null
        allEntries = emptyList()
    }
}
