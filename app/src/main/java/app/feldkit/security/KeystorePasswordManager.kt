package app.feldkit.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Saves and retrieves volume passwords using AES-GCM keys stored in the Android Keystore.
 *
 * Each password is encrypted with a per-alias AES-GCM key that never leaves the secure hardware.
 * The ciphertext and IV are stored in SharedPreferences alongside the volume UUID.
 */
class KeystorePasswordManager(private val context: Context) {

    companion object {
        private const val TAG = "FeldKit"
        private const val PREF_NAME = "drive_passwords"
        private const val KEY_ALIAS = "feldkit_password_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return kg.generateKey()
    }

    /**
     * Encrypt and store a password for the given volume UUID.
     */
    fun savePassword(volumeUuid: String, password: String) {
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString("${volumeUuid}.enc", Base64.encodeToString(encrypted, Base64.DEFAULT))
                .putString("${volumeUuid}.iv", Base64.encodeToString(iv, Base64.DEFAULT))
                .apply()
            Log.i(TAG, "Password saved for volume $volumeUuid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save password", e)
        }
    }

    /**
     * Retrieve and decrypt the stored password for the given volume UUID.
     * Returns null if no password is stored or decryption fails.
     */
    fun getPassword(volumeUuid: String): String? {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val encStr = prefs.getString("${volumeUuid}.enc", null) ?: return null
            val ivStr = prefs.getString("${volumeUuid}.iv", null) ?: return null

            val encrypted = Base64.decode(encStr, Base64.DEFAULT)
            val iv = Base64.decode(ivStr, Base64.DEFAULT)

            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get password", e)
            null
        }
    }

    /**
     * Remove the stored password for the given volume UUID.
     */
    fun deletePassword(volumeUuid: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove("${volumeUuid}.enc")
            .remove("${volumeUuid}.iv")
            .apply()
    }

    /**
     * Returns true if a password has been saved for this volume UUID.
     */
    fun hasPassword(volumeUuid: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.contains("${volumeUuid}.enc")
    }
}
