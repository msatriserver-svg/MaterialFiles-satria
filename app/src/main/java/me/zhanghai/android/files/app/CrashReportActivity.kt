/*
 * Satria build: shows the last crash. Runs in its own process (see the manifest), so it works
 * even when the app crashes while starting up.
 */

package me.zhanghai.android.files.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import me.zhanghai.android.files.filelist.FileListActivity

class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = try {
            CrashReporter.getReportFile(this).readText()
        } catch (e: Exception) {
            "(Tidak ada laporan: $e)"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = "Aplikasi mengalami error.\n\nSalin atau bagikan teks di bawah ini lalu kirim " +
                "ke admin. Setelah itu tekan \"Buka lagi\": aplikasi dibuka dalam mode aman " +
                "(satu panel) sampai laporan ini dihapus."
            textSize = 15f
        }
        val reportText = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, padding, 0, padding)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("Salin") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Laporan error", report))
                Toast.makeText(this@CrashReportActivity, "Tersalin", Toast.LENGTH_SHORT).show()
            })
            addView(button("Bagikan") {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, report), "Bagikan laporan error"
                    )
                )
            })
            addView(button("Buka lagi (mode aman)") {
                startActivity(
                    Intent(this@CrashReportActivity, FileListActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            })
            addView(button("Hapus laporan dan tutup") {
                CrashReporter.getReportFile(this@CrashReportActivity).delete()
                finish()
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding * 2)
            addView(title)
            addView(reportText)
            addView(buttons)
        }
        setContentView(ScrollView(this).apply {
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        })
    }
}
