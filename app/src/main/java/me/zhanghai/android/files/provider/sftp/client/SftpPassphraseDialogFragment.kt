/*
 * Satria build: asks for the passphrase of an encrypted SFTP private key when it is not saved.
 * Based on ArchivePasswordDialogFragment (Copyright (c) 2023 Hai Zhang), GPL-3.0.
 */

package me.zhanghai.android.files.provider.sftp.client

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.requireViewByIdCompat
import me.zhanghai.android.files.databinding.ArchivePasswordDialogBinding
import me.zhanghai.android.files.ui.AllowSoftInputHackAlertDialogCustomView
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.RemoteCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getArgs
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.readParcelable
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener

class SftpPassphraseDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: ArchivePasswordDialogBinding

    private var isListenerNotified = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val hierarchyState = SparseArray<Parcelable>()
            .apply { binding.root.saveHierarchyState(this) }
        outState.putState(State(hierarchyState))
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val authority = args.authority
        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(R.string.sftp_passphrase_title)
            .setMessage(
                getString(
                    R.string.sftp_passphrase_message_format, authority.username, authority.host,
                    authority.port
                )
            )
            .apply {
                binding = ArchivePasswordDialogBinding.inflate(context.layoutInflater)
                binding.passwordEdit.hideTextInputLayoutErrorOnTextChange(binding.passwordLayout)
                binding.passwordEdit.setOnEditorConfirmActionListener { onOk() }
                if (savedInstanceState != null) {
                    val state = savedInstanceState.getState<State>()
                    binding.root.restoreHierarchyState(state.hierarchyState)
                } else if (args.isRetry) {
                    binding.passwordLayout.error = getString(R.string.sftp_passphrase_error_wrong)
                }
                setView(AllowSoftInputHackAlertDialogCustomView(context))
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                // Override the listener here so that we have control over when to close the dialog.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
                window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
    }

    override fun onStart() {
        super.onStart()

        val dialog = requireDialog() as AlertDialog
        if (binding.root.parent == null) {
            val scrollView = dialog.requireViewByIdCompat<NestedScrollView>(R.id.scrollView)
            val linearLayout = scrollView.getChildAt(0) as LinearLayout
            linearLayout.addView(binding.root)
            binding.passwordEdit.requestFocus()
        }
    }

    private fun onOk() {
        val passphrase = binding.passwordEdit.text!!.toString()
        if (passphrase.isEmpty()) {
            binding.passwordLayout.error = getString(R.string.sftp_passphrase_error_empty)
            return
        }
        notifyListenerOnce(passphrase)
        finish()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        notifyListenerOnce(null)
        finish()
    }

    fun onFinish() {
        notifyListenerOnce(null)
    }

    private fun notifyListenerOnce(passphrase: String?) {
        if (isListenerNotified) {
            return
        }
        args.listener(passphrase)
        isListenerNotified = true
    }

    @Parcelize
    class Args(
        val authority: Authority,
        val isRetry: Boolean,
        val listener: @WriteWith<ListenerParceler>()
        (String?) -> Unit
    ) : ParcelableArgs {
        object ListenerParceler : Parceler<(String?) -> Unit> {
            override fun create(parcel: Parcel): (String?) -> Unit =
                parcel.readParcelable<RemoteCallback>()!!.let {
                    { passphrase ->
                        it.sendResult(Bundle().putArgs(ListenerArgs(passphrase)))
                    }
                }

            override fun ((String?) -> Unit).write(parcel: Parcel, flags: Int) {
                parcel.writeParcelable(
                    RemoteCallback {
                        val args = it.getArgs<ListenerArgs>()
                        this(args.passphrase)
                    }, flags
                )
            }

            @Parcelize
            private class ListenerArgs(
                val passphrase: String?
            ) : ParcelableArgs
        }
    }

    @Parcelize
    private class State(
        val hierarchyState: SparseArray<Parcelable>
    ) : ParcelableState
}
