package app.feldkit.storage.filesystem.luks

import android.util.Log
import app.feldkit.storage.crypto.Luks
import app.feldkit.storage.crypto.LuksDevice
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.FilesystemMounter
import app.feldkit.storage.filesystem.VolumeInfo
import java.io.OutputStream

/** A LUKS1 / LUKS2 container (Linux disk encryption): locked until the passphrase is given, then it shows the file system inside. Read-only. */
class LuksReader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    private var info: Luks.Info? = null
    private var inner: FileSystemReader? = null

    override fun mount(): Boolean { info = Luks.load(dev, startLba); return info != null }

    override fun isLocked(volumeIndex: Int) = inner == null

    override fun unlock(volumeIndex: Int, secret: String): Boolean {
        val i = info ?: return false
        if (inner != null) return true
        i.unsupported?.let { Log.w("FeldKit", "LUKS: $it"); return false }
        val key = Luks.unlock(dev, startLba, i, secret) ?: return false
        val plain = LuksDevice(dev, startLba, i, key)
        val m = FilesystemMounter.mountAll(plain).firstOrNull() ?: return false.also { Log.w("FeldKit", "LUKS: unlocked, but no known file system inside") }
        inner = m.reader
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        inner?.getVolumeInfos()?.let { list -> return list.map { it.copy(isEncrypted = true, encryption = "LUKS") } }
        val i = info
        val note = i?.unsupported?.let { " ($it)" } ?: ""
        return listOf(VolumeInfo(name = "LUKS${i?.version ?: ""} volume$note", type = "LUKS", totalBlocks = 0, blockSize = 512, isEncrypted = true, encryption = "LUKS"))
    }

    override fun listDirectory(volumeIndex: Int, path: String) = inner?.listDirectory(0, path) ?: emptyList()
    override fun readFile(entry: FileSystemEntry) = inner?.readFile(entry)
    override fun readFileTo(entry: FileSystemEntry, out: OutputStream, onProgress: ((Long) -> Unit)?) = inner?.readFileTo(entry, out, onProgress) ?: false
    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int) = inner?.readRange(entry, offset, buf, bufOff, len) ?: -1
    override fun rootEntry(volumeIndex: Int) = inner?.rootEntry(0) ?: super.rootEntry(volumeIndex)
    override fun unmount() { inner?.unmount(); inner = null; info = null }
}
