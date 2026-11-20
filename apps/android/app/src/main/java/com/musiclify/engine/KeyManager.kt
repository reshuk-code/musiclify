package com.musiclify.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key handling.
 *
 * A master key lives in the Android Keystore (hardware-backed where available) and never
 * leaves it. It wraps a random media key, which encrypts the downloaded songs with AES-CTR
 * (a software key is needed because streaming megabytes through the Keystore is too slow).
 * Songs copied off the device are therefore unplayable.
 */
class KeyManager(context: Context) {
    private val sp = context.getSharedPreferences("musiclify_keys", Context.MODE_PRIVATE)

    /** 128-bit AES key for the encrypted media cache. */
    val mediaKey: ByteArray by lazy { loadOrCreate("media_key", 16) }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): ByteArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
    }

    private fun loadOrCreate(name: String, size: Int): ByteArray {
        sp.getString(name, null)?.let { return decrypt(it) }
        val key = ByteArray(size).also { SecureRandom().nextBytes(it) }
        sp.edit(commit = true) { putString(name, encrypt(key)) }
        return key
    }

    @Synchronized
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "musiclify_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
