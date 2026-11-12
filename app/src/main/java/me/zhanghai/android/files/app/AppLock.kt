/*
 * App lock (Satria build): require biometric or device credential when entering the app.
 */

package me.zhanghai.android.files.app

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.valueCompat

object AppLock {
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    // Locked on every fresh process start.
    private var isUnlocked = false
    private var stoppedAt: Long? = null
    // While the prompt (or the system credential screen on old Android) is showing, the process
    // may go to the background; that must not count as leaving the app.
    var isAuthenticating = false
        private set

    val isEnabled: Boolean
        get() = Settings.APP_LOCK.valueCompat

    val isLocked: Boolean
        get() = isEnabled && !isUnlocked

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val stoppedAt = stoppedAt ?: return
                this@AppLock.stoppedAt = null
                val timeoutMillis =
                    (Settings.APP_LOCK_TIMEOUT.valueCompat.toLongOrNull() ?: 60L) * 1000
                if (SystemClock.elapsedRealtime() - stoppedAt >= timeoutMillis) {
                    isUnlocked = false
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!isAuthenticating) {
                    stoppedAt = SystemClock.elapsedRealtime()
                }
            }
        })
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun buildPromptInfo(activity: FragmentActivity, titleRes: Int): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titleRes))
            .setSubtitle(activity.getString(R.string.app_lock_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

    private fun callback(onResult: (Boolean) -> Unit) =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isAuthenticating = false
                isUnlocked = true
                stoppedAt = null
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isAuthenticating = false
                onResult(false)
            }
            // onAuthenticationFailed: a single bad attempt, the prompt stays open.
        }

    /** Used by Settings to confirm before turning the lock on or off. */
    fun confirm(fragment: Fragment, onResult: (Boolean) -> Unit) {
        val activity = fragment.requireActivity()
        isAuthenticating = true
        BiometricPrompt(
            fragment, ContextCompat.getMainExecutor(activity), callback(onResult)
        ).authenticate(buildPromptInfo(activity, R.string.app_lock_confirm_title))
    }

    /** Covers an activity's content until the user authenticates. */
    class Guard(private val activity: FragmentActivity) {
        private val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity), callback { success ->
                if (success) {
                    removeOverlay()
                } else {
                    unlockButton?.visibility = View.VISIBLE
                }
            }
        )
        private var overlay: View? = null
        private var unlockButton: Button? = null
        // Back while locked leaves the app instead of acting on the hidden content.
        private var backCallback: OnBackPressedCallback? = null

        fun onResume() {
            if (!isLocked) {
                removeOverlay()
                return
            }
            addOverlay()
            if (!isAuthenticating) {
                authenticate()
            }
        }

        private fun authenticate() {
            if (!canAuthenticate(activity)) {
                // Device lock was removed: nothing to authenticate against, don't lock the user out.
                isUnlocked = true
                removeOverlay()
                return
            }
            isAuthenticating = true
            unlockButton?.visibility = View.INVISIBLE
            prompt.authenticate(buildPromptInfo(activity, R.string.app_lock_title))
        }

        private fun addOverlay() {
            if (overlay != null) {
                return
            }
            val density = activity.resources.displayMetrics.density
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(activity).apply {
                    setText(R.string.app_lock_locked)
                    textSize = 18f
                    setTextColor(activity.getColorByAttr(android.R.attr.textColorPrimary))
                    gravity = Gravity.CENTER
                })
                addView(Button(activity).also { unlockButton = it }.apply {
                    setText(R.string.app_lock_unlock)
                    visibility = View.INVISIBLE
                    setOnClickListener { authenticate() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (16 * density).toInt() })
            }
            val overlay = FrameLayout(activity).apply {
                setBackgroundColor(activity.getColorByAttr(android.R.attr.colorBackground))
                isClickable = true
                isFocusable = true
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
            }
            (activity.window.decorView as ViewGroup).addView(
                overlay, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            this.overlay = overlay
            backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    activity.finishAffinity()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
        }

        private fun removeOverlay() {
            val overlay = overlay ?: return
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            this.overlay = null
            unlockButton = null
            backCallback?.remove()
            backCallback = null
        }
    }
}
