/*
 * Satria build: encrypts sensitive preference values (saved servers with their passwords and
 * private keys) with an AES-256-GCM key that lives in the Android Keystore and never leaves it.
 */

package me.zhanghai.android.files.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretBox {
    private const val KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "satria_settings_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    @Synchronized
    private fun getKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        if (!create) {
            return null
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
            .apply { init(spec) }
            .generateKey()
    }

    @Throws(Exception::class)
    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true))
        val iv = cipher.iv
        check(iv.size == IV_SIZE)
        return PREFIX + Base64.encodeToString(iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    @Throws(Exception::class)
    fun decrypt(value: String): ByteArray {
        val data = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
        val key = getKey(false) ?: throw IllegalStateException("Keystore key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }
}
