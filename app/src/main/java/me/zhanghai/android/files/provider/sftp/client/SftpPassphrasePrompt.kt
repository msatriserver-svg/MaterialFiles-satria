/*
 * Satria build: the passphrase of an encrypted private key can be left unsaved. It is then asked
 * when a new SFTP connection is opened, and only kept in memory for a short while.
 *
 * Only one dialog is shown per server at a time: a caller that comes while a dialog is open (or
 * whose earlier wait was interrupted, e.g. a folder load cancelled and restarted) waits for that
 * same dialog instead of opening another one. A passphrase that was just entered and checked is
 * reused for connections opened within a short window, so a burst of connections (both panes, a
 * retry on a dropped connection) asks only once.
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

object SftpPassphrasePrompt {
    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_ATTEMPTS = 3
    // Calls queued behind a cancelled prompt fail at once instead of asking again right away.
    private const val CANCEL_COOLDOWN_MILLIS = 5_000L
    // A passphrase just entered is reused by connections opened within this window.
    private const val REUSE_MILLIS = 60_000L

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile
        var result: String? = null
        @Volatile
        var answerTime = 0L
    }

    private class Recent(val passphrase: String, val time: Long)

    private val lock = Any()
    private val lastCancelTimes = mutableMapOf<Authority, Long>()
    private val pendings = mutableMapOf<Authority, Pending>()
    private val recents = mutableMapOf<Authority, Recent>()

    @Throws(ClientException::class)
    fun request(authority: Authority, privateKey: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Waiting for the dialog here would freeze the UI that has to show it.
            throw ClientException(application.getString(R.string.sftp_passphrase_error_cancelled))
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val lastCancelTime = lastCancelTimes[authority]
            if (lastCancelTime != null && now - lastCancelTime < CANCEL_COOLDOWN_MILLIS) {
                throw ClientException(
                    application.getString(R.string.sftp_passphrase_error_cancelled)
                )
            }
            val recent = recents[authority]
            if (recent != null) {
                if (now - recent.time < REUSE_MILLIS) {
                    return recent.passphrase
                }
                recents -= authority
            }
        }
        var isRetry = false
        repeat(MAX_ATTEMPTS) {
            val passphrase = ask(authority, isRetry)
            if (passphrase == null) {
                synchronized(lock) {
                    lastCancelTimes[authority] = SystemClock.elapsedRealtime()
                }
                throw ClientException(
                    application.getString(R.string.sftp_passphrase_error_cancelled)
                )
            }
            if (PublicKeyAuthentication.validate(privateKey, passphrase) == null) {
                synchronized(lock) {
                    recents[authority] = Recent(passphrase, SystemClock.elapsedRealtime())
                }
                return passphrase
            }
            isRetry = true
        }
        throw ClientException(application.getString(R.string.sftp_passphrase_error_wrong))
    }

    private fun ask(authority: Authority, isRetry: Boolean): String? {
        var pending: Pending? = null
        var isNew = false
        synchronized(lock) {
            val existing = pendings[authority]
            if (existing != null && existing.latch.count == 0L) {
                // Answered while nobody was waiting (the earlier wait was interrupted).
                pendings -= authority
                // A cancel (null) counts too, so a cancelled dialog is not shown again at once.
                if (!isRetry &&
                    SystemClock.elapsedRealtime() - existing.answerTime < REUSE_MILLIS) {
                    return existing.result
                }
            } else if (existing != null && !isRetry) {
                // A dialog for this server is already open: wait for it below.
                pending = existing
            }
            if (pending == null) {
                pending = Pending().also { pendings[authority] = it }
                isNew = true
            }
        }
        val thePending = pending!!
        if (isNew) {
            val intent = SftpPassphraseDialogActivity::class.createIntent()
                .putArgs(SftpPassphraseDialogFragment.Args(authority, isRetry) {
                    thePending.result = it
                    thePending.answerTime = SystemClock.elapsedRealtime()
                    thePending.latch.countDown()
                })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            application.startActivity(intent)
        }
        return await(authority, thePending)
    }

    // Throws InterruptedException if the waiting thread is interrupted; the pending dialog is kept
    // so that the next caller picks up its answer.
    private fun await(authority: Authority, pending: Pending): String? {
        val isAnswered = pending.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(lock) {
            if (pendings[authority] === pending) {
                pendings -= authority
            }
        }
        return if (isAnswered) pending.result else null
    }
}
