package com.raspi.piblue

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught crash so a phone in the field can report itself.
 *
 * The file lands in the app's private external directory
 * (`Android/data/com.raspi.piblue/files/crash-last.txt`), which needs no permission and is
 * readable from a file manager or `adb pull`. [MainActivity] shows it once on the next launch.
 *
 * Crashes on BLE callback / worker threads are exactly the kind that are invisible without
 * logcat, so the handler is installed on the default (all-thread) hook, not per-thread.
 */
class PiBlueApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            // Let the platform do its normal work (kill the process, show the dialog).
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(thread: Thread, error: Throwable) {
        val trace = StringWriter()
        PrintWriter(trace).use { error.printStackTrace(it) }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"

        val text = buildString {
            append("时间: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            append("版本: ").append(version).append('\n')
            append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程: ").append(thread.name).append('\n')
            append('\n').append(trace)
        }

        Log.e(TAG, text)
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, CRASH_FILE).writeText(text)
    }

    companion object {
        const val CRASH_FILE = "crash-last.txt"
        private const val TAG = "PiBlue/CRASH"
    }
}
