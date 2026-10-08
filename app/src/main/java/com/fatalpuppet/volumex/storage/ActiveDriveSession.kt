package com.fatalpuppet.volumex.storage

import com.fatalpuppet.volumex.storage.filesystem.FileSystemReader
import com.fatalpuppet.volumex.storage.filesystem.VolumeInfo

/**
 * Singleton that holds the active drive reader, available to ContentProviders
 * and other components that cannot easily receive a ViewModel reference.
 */
object ActiveDriveSession {
    @Volatile var reader: FileSystemReader? = null
    @Volatile var currentVolumeIndex: Int = 0
    @Volatile var volumes: List<VolumeInfo> = emptyList()
    @Volatile var currentPath: String = "/"

    fun clear() {
        reader = null
        currentVolumeIndex = 0
        volumes = emptyList()
        currentPath = "/"
    }
}
