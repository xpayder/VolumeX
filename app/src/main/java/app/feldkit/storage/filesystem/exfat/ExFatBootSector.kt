package app.feldkit.storage.filesystem.exfat

data class ExFatBootSector(
    val partitionOffset: Long,
    val volumeLength: Long,
    val fatOffset: Long,
    val fatLength: Long,
    val clusterHeapOffset: Long,
    val clusterCount: Long,
    val rootDirectoryCluster: Long,
    val bytesPerSectorShift: Int,
    val sectorsPerClusterShift: Int,
    val numberOfFats: Int,
    val volumeSerialNumber: Long,
    val partitionStartLba: Long = 0L
) {
    val bytesPerSector: Int
        get() = 1 shl bytesPerSectorShift

    val sectorsPerCluster: Int
        get() = 1 shl sectorsPerClusterShift

    val bytesPerCluster: Int
        get() = bytesPerSector * sectorsPerCluster
}