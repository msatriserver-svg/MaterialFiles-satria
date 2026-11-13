/*
 * Satria build: the passphrase of an encrypted private key can be left unsaved. It is then asked
 * every time a new SFTP connection is opened, and only kept in memory for that connection attempt.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SftpPassphrasePrompt {
    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_ATTEMPTS = 3
    // Calls queued behind a cancelled prompt fail at once instead of asking again right away.
    private const val CANCEL_COOLDOWN_MILLIS = 5_000L

    private val lastCancelTimes = mutableMapOf<Authority, Long>()

    @Throws(ClientException::class)
    fun request(authority: Authority, privateKey: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Waiting for the dialog here would freeze the UI that has to show it.
            throw ClientException(application.getString(R.string.sftp_passphrase_error_cancelled))
        }
        synchronized(lastCancelTimes) {
            val lastCancelTime = lastCancelTimes[authority]
            if (lastCancelTime != null &&
                SystemClock.elapsedRealtime() - lastCancelTime < CANCEL_COOLDOWN_MILLIS) {
                throw ClientException(
                    application.getString(R.string.sftp_passphrase_error_cancelled)
                )
            }
        }
        var isRetry = false
        repeat(MAX_ATTEMPTS) {
            val passphrase = ask(authority, isRetry)
            if (passphrase == null) {
                synchronized(lastCancelTimes) {
                    lastCancelTimes[authority] = SystemClock.elapsedRealtime()
                }
                throw ClientException(
                    application.getString(R.string.sftp_passphrase_error_cancelled)
                )
            }
            if (PublicKeyAuthentication.validate(privateKey, passphrase) == null) {
                return passphrase
            }
            isRetry = true
        }
        throw ClientException(application.getString(R.string.sftp_passphrase_error_wrong))
    }

    private fun ask(authority: Authority, isRetry: Boolean): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val intent = SftpPassphraseDialogActivity::class.createIntent()
            .putArgs(SftpPassphraseDialogFragment.Args(authority, isRetry) {
                result.set(it)
                latch.countDown()
            })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return null
        }
        return result.get()
    }
}
