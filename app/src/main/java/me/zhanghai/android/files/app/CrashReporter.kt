/*
 * Satria build: when the app crashes, keep the error on the device and show it on screen, so it
 * can be reported without a computer. Nothing is sent anywhere.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import me.zhanghai.android.files.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun getReportFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                getReportFile(appContext).writeText(buildReport(thread, throwable))
                appContext.startActivity(
                    Intent(appContext, CrashReportActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (e: Throwable) {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return buildString {
            appendLine("Material Files ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("$time, thread ${thread.name}")
            appendLine()
            append(stackTrace)
        }
    }
}
