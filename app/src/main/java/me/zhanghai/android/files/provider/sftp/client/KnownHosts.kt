/*
 * Host key pinning (Satria build): trust on first use, refuse when the key changes.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import me.zhanghai.android.files.app.application
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

object KnownHosts {
    private val preferences: SharedPreferences by lazy {
        application.getSharedPreferences("sftp_known_hosts", Context.MODE_PRIVATE)
    }

    private fun id(hostname: String, port: Int): String = "[${hostname.lowercase()}]:$port"

    private fun fingerprint(key: PublicKey): String {
        val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun clear() {
        synchronized(this) { preferences.edit().clear().commit() }
    }

    class Verifier : HostKeyVerifier {
        /** Set when the server presented a key different from the pinned one. */
        @Volatile
        var mismatch: Mismatch? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val id = id(hostname, port)
            val entry = "${KeyType.fromKey(key)} ${fingerprint(key)}"
            synchronized(KnownHosts) {
                val pinned = preferences.getString(id, null)
                return when (pinned) {
                    null -> preferences.edit().putString(id, entry).commit()
                    entry -> true
                    else -> {
                        mismatch = Mismatch(pinned, entry)
                        false
                    }
                }
            }
        }

        // Ask the server for the key type we pinned, so that a server offering several host key
        // types doesn't look like a changed key.
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
            val pinned = synchronized(KnownHosts) { preferences.getString(id(hostname, port), null) }
                ?: return emptyList()
            return when (val type = pinned.substringBefore(' ')) {
                "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")
                else -> listOf(type)
            }
        }
    }

    class Mismatch(val pinned: String, val presented: String)
}
