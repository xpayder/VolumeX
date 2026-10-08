package com.fatalpuppet.volumex.storage.crypto

import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader

/**
 * High-level FileVault decryptor for APFS encrypted volumes.
 *
 * Usage:
 *   1. Create with the block device reader, partition start LBA, and the raw keybag data.
 *   2. Call unlock(password) or unlockWithRecoveryKey(recoveryKeyBase32).
 *   3. If successful, use decryptBlock() to get plaintext data blocks.
 */
class FileVaultDecryptor(
    private val blockDevice: BlockDeviceReader,
    private val partitionStartLba: Long,
    private val keybagData: ByteArray
) {
    companion object {
        private const val TAG = "VolumeX"
        private const val APFS_BLOCK_SIZE = 4096
        private const val SECTOR_SIZE = 512
    }

    private var aesXts: AesXts? = null
    private var isUnlocked = false

    /**
     * Attempt to unlock the volume using a user password.
     * Returns true if the password was correct and keys were derived successfully.
     */
    fun unlock(password: String): Boolean {
        val entries = ApfsKeybag.parse(keybagData)
        if (entries.isEmpty()) {
            Log.w(TAG, "FileVault: keybag is empty or failed to parse")
            return false
        }

        for (entry in entries) {
            val params = entry.pbkdf2Params ?: continue
            val wrappedVek = entry.wrappedVek ?: entry.wrappedKek ?: continue

            val kek = ApfsKeyDerivation.deriveKeyFromPassword(
                password = password,
                salt = params.salt,
                iterations = params.iterations,
                keyLenBits = params.keyLenBits.coerceIn(128, 256)
            )

            val vek = ApfsKeybag.aesKeyUnwrap(wrappedVek, kek)
            if (vek != null) {
                Log.i(TAG, "FileVault: unlocked with password, VEK length=${vek.size}")
                aesXts = AesXts(vek)
                isUnlocked = true
                return true
            }
        }

        Log.w(TAG, "FileVault: wrong password — no keybag entry could be unwrapped")
        return false
    }

    /**
     * Attempt to unlock using a Personal Recovery Key (base-32 string).
     */
    fun unlockWithRecoveryKey(recoveryKeyBase32: String): Boolean {
        val recoveryBytes = ApfsKeyDerivation.decodeBase32RecoveryKey(recoveryKeyBase32)
        if (recoveryBytes.isEmpty()) return false

        val entries = ApfsKeybag.parse(keybagData)
        for (entry in entries) {
            val params = entry.pbkdf2Params
            val wrappedVek = entry.wrappedVek ?: entry.wrappedKek ?: continue

            val kek = if (params != null) {
                ApfsKeyDerivation.deriveKeyFromRecoveryKey(
                    recoveryKeyBytes = recoveryBytes,
                    salt = params.salt,
                    iterations = params.iterations,
                    keyLenBits = params.keyLenBits.coerceIn(128, 256)
                )
            } else {
                // Direct use without PBKDF2
                recoveryBytes
            }

            val vek = ApfsKeybag.aesKeyUnwrap(wrappedVek, kek)
            if (vek != null) {
                Log.i(TAG, "FileVault: unlocked with recovery key")
                aesXts = AesXts(vek)
                isUnlocked = true
                return true
            }
        }

        Log.w(TAG, "FileVault: recovery key did not unlock any keybag entry")
        return false
    }

    /**
     * Read and decrypt a single APFS block (4096 bytes by default).
     *
     * @param blockNumber  Physical block number (absolute from partition start).
     * @return Decrypted block bytes, or null if read failed.
     */
    fun decryptBlock(blockNumber: Long): ByteArray? {
        val xts = aesXts
        if (!isUnlocked || xts == null) {
            Log.w(TAG, "FileVault: attempted to read block while locked")
            return null
        }

        val sectorsPerBlock = APFS_BLOCK_SIZE / SECTOR_SIZE
        val firstSectorOfBlock = (partitionStartLba + blockNumber) * sectorsPerBlock

        val blockData = ByteArray(APFS_BLOCK_SIZE)
        var offset = 0
        for (s in 0 until sectorsPerBlock) {
            val sectorData = blockDevice.readSector(firstSectorOfBlock + s) ?: return null
            sectorData.copyInto(blockData, offset)
            offset += sectorData.size
        }

        return xts.decryptBlock(blockData, firstSectorOfBlock)
    }

    fun isUnlocked(): Boolean = isUnlocked

    fun lock() {
        aesXts = null
        isUnlocked = false
    }
}
