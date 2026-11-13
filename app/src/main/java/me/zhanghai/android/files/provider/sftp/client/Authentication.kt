/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException

sealed class Authentication : Parcelable {
    abstract fun toAuthMethod(): AuthMethod
}

@Parcelize
data class PasswordAuthentication(
    val password: String
) : Authentication() {
    override fun toAuthMethod(): AuthMethod =
        AuthPassword(PasswordUtils.createOneOff(password.toCharArray()))
}

@Parcelize
data class PublicKeyAuthentication(
    val privateKey: String,
    val privateKeyPassword: String?
) : Authentication() {
    override fun toAuthMethod(): AuthMethod =
        AuthPublickey(createKeyProvider(privateKey, privateKeyPassword))

    companion object {
        private val KEY_PROVIDER_FACTORIES = DefaultConfig().fileKeyProviderFactories

        /**
         * Satria build: whether the key needs a passphrase. Detected by whether the key file
         * asks for one, so it works for every key format sshj supports.
         */
        fun isEncrypted(privateKey: String): Boolean {
            var isPasswordRequested = false
            val passwordFinder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray {
                    isPasswordRequested = true
                    return CharArray(0)
                }

                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            try {
                createKeyProvider(privateKey, passwordFinder).private
            } catch (e: Exception) {
                // Expected for an encrypted key, or for an invalid one.
            }
            return isPasswordRequested
        }

        fun validate(privateKey: String, privateKeyPassword: String?): IOException? =
            try {
                createKeyProvider(privateKey, privateKeyPassword).private
                null
            } catch (e: IOException) {
                e
            }

        /**
         * @see net.schmizz.sshj.SSHClient.loadKeys
         */
        @Throws(IOException::class)
        private fun createKeyProvider(
            privateKey: String,
            privateKeyPassword: String?
        ): KeyProvider =
            createKeyProvider(
                privateKey, privateKeyPassword?.let { PasswordUtils.createOneOff(it.toCharArray()) }
            )

        @Throws(IOException::class)
        private fun createKeyProvider(
            privateKey: String,
            passwordFinder: PasswordFinder?
        ): KeyProvider {
            val format = KeyProviderUtil.detectKeyFileFormat(privateKey, false)
            val keyProvider = Factory.Named.Util.create(KEY_PROVIDER_FACTORIES, format.toString())
                ?: throw IOException("No key provider factory found for $format")
            keyProvider.init(privateKey, null, passwordFinder)
            return keyProvider
        }
    }
}
