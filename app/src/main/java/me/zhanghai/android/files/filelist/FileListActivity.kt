/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.SftpServer
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.isOrientationLandscape
import me.zhanghai.android.files.util.valueCompat
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.CrashReporter
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class FileListActivity : AppActivity() {
    // Satria build: one fragment normally, two in dual pane (commander) mode.
    private lateinit var fragments: List<FileListFragment>

    private var isDualPane = false

    /** Index of the pane the user touched last; back and keyboard shortcuts go to it. */
    val activePaneLiveData = MutableLiveData(0)

    private val paneViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A restored instance keeps the mode it was created with, so that its fragments find
        // their containers again.
        isDualPane = savedInstanceState?.getBoolean(STATE_DUAL_PANE)
            ?: (Settings.FILE_LIST_DUAL_PANE.valueCompat && intent.action !in PICK_ACTIONS
                // Safe mode after a crash, until the report is dismissed.
                && !CrashReporter.hasReport(this))
        if (isDualPane) {
            onCreateDualPane(savedInstanceState)
            return
        }
        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        val fragment: FileListFragment
        if (savedInstanceState == null) {
            fragment = FileListFragment().putArgs(FileListFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as FileListFragment
        }
        fragments = listOf(fragment)
    }

    private fun onCreateDualPane(savedInstanceState: Bundle?) {
        setContentView(createDualPaneView())
        if (savedInstanceState == null) {
            val firstFragment = FileListFragment().putArgs(FileListFragment.Args(intent, 0))
            // Like WinSCP: local on one side, the (first) saved SFTP server on the other.
            val remotePath = Settings.STORAGES.valueCompat.firstOrNull { it is SftpServer }?.path
            val secondIntent = if (remotePath != null) {
                createViewIntent(remotePath)
            } else {
                FileListActivity::class.createIntent()
            }
            val secondFragment = FileListFragment().putArgs(
                FileListFragment.Args(secondIntent, 1)
            )
            supportFragmentManager.commit {
                add(R.id.file_list_pane_first, firstFragment)
                add(R.id.file_list_pane_second, secondFragment)
            }
            fragments = listOf(firstFragment, secondFragment)
        } else {
            fragments = listOf(R.id.file_list_pane_first, R.id.file_list_pane_second).map {
                supportFragmentManager.findFragmentById(it) as FileListFragment
            }
            activePaneLiveData.value = savedInstanceState.getInt(STATE_ACTIVE_PANE)
        }
    }

    private fun createDualPaneView(): View {
        val isLandscape = isOrientationLandscape
        val density = resources.displayMetrics.density
        val panesLayout = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val paneIds = listOf(R.id.file_list_pane_first, R.id.file_list_pane_second)
        paneIds.forEachIndexed { index, paneId ->
            if (index > 0) {
                val dividerSize = (2 * density).toInt()
                val divider = View(this).apply {
                    setBackgroundColor(
                        ColorUtils.setAlphaComponent(
                            getColorByAttr(android.R.attr.textColorPrimary), 0x40
                        )
                    )
                }
                panesLayout.addView(
                    divider, if (isLandscape) {
                        LinearLayout.LayoutParams(dividerSize, MATCH_PARENT)
                    } else {
                        LinearLayout.LayoutParams(MATCH_PARENT, dividerSize)
                    }
                )
            }
            val paneView = FrameLayout(this).apply {
                id = paneId
                // Both panes contain views with the same IDs; each fragment saves the state of
                // its own views, the window must not save them a second time and mix them up.
                isSaveFromParentEnabled = false
            }
            paneViews += paneView
            panesLayout.addView(
                paneView, if (isLandscape) {
                    LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                } else {
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                }
            )
        }
        // The panes must not each make room for the status and navigation bars, so do that once
        // here and hand no insets down.
        val statusBarView = View(this).apply {
            setBackgroundColor(getColorByAttr(R.attr.colorAppBarSurface))
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBarView, LinearLayout.LayoutParams(MATCH_PARENT, 0))
            addView(panesLayout, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime()
            )
            statusBarView.updateLayoutParams { height = bars.top }
            panesLayout.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return rootLayout
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isDualPane && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val rect = Rect()
            val index = paneViews.indexOfFirst {
                it.getGlobalVisibleRect(rect) && rect.contains(event.x.toInt(), event.y.toInt())
            }
            if (index != -1 && activePaneLiveData.value != index) {
                activePaneLiveData.value = index
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** The directory shown by the pane opposite to [paneIndex], or null in single pane mode. */
    fun getOtherPanePath(paneIndex: Int): Path? =
        if (isDualPane) fragments.getOrNull(1 - paneIndex)?.paneCurrentPath else null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(STATE_DUAL_PANE, isDualPane)
        outState.putInt(STATE_ACTIVE_PANE, activePaneLiveData.value ?: 0)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val fragment = fragments.getOrNull(activePaneLiveData.value ?: 0) ?: fragments.first()
        if (fragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val STATE_DUAL_PANE = "dual_pane"
        private const val STATE_ACTIVE_PANE = "active_pane"

        // Pickers opened by other apps (or by our own dialogs) stay single pane.
        private val PICK_ACTIONS = setOf(
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_CREATE_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE
        )

        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
