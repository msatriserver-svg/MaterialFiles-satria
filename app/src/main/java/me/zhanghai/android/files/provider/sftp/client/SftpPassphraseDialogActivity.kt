/*
 * Satria build. Based on ArchivePasswordDialogActivity (Copyright (c) 2023 Hai Zhang), GPL-3.0.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs

class SftpPassphraseDialogActivity : AppActivity() {
    private val args by args<SftpPassphraseDialogFragment.Args>()

    private lateinit var fragment: SftpPassphraseDialogFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            fragment = SftpPassphraseDialogFragment().putArgs(args)
            supportFragmentManager.commit {
                add(fragment, SftpPassphraseDialogFragment::class.java.name)
            }
        } else {
            fragment = supportFragmentManager.findFragmentByTag(
                SftpPassphraseDialogFragment::class.java.name
            ) as SftpPassphraseDialogFragment
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) {
            fragment.onFinish()
        }
    }
}
