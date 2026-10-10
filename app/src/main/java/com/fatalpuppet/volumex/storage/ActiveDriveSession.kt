package com.fatalpuppet.volumex.storage

import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.FileSystemWriter
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo

/**
 * Singleton that holds the active drive reader, available to ContentProviders
 * and other components that cannot easily receive a ViewModel reference.
 */
object ActiveDriveSession {
    @Volatile var reader: FileSystemReader? = null
    @Volatile var writer: FileSystemWriter? = null
    @Volatile var currentVolumeIndex: Int = 0
    @Volatile var volumes: List<VolumeInfo> = emptyList()
    /** The raw block device, so a verification pass can flush writes and drop read caches before reading a file back. */
    @Volatile var device: com.fatalpuppet.volumex.storage.disk.BlockDeviceReader? = null
    @Volatile var currentPath: String = "/"

    fun clear() {
        reader = null
        writer = null
        currentVolumeIndex = 0
        volumes = emptyList()
        device = null
        currentPath = "/"
    }
}
