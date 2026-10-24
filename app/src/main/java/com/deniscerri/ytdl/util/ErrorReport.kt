package com.deniscerri.ytdl.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 69 mod: keeps the last crash or hidden error in a file and shows it (with a Copy button) the next time the app opens. */
object ErrorReport {
    private fun file(context: Context) = File(context.filesDir, "last_error_69.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, "Crash in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, what: String, error: Throwable) {
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val old = file(context).takeIf { it.exists() }?.readText().orEmpty().take(6000)
            file(context).writeText("$time  $what\n${error.stackTraceToString().take(6000)}\n\n$old")
        }
    }

    fun showIfAny(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return
        AlertDialog.Builder(activity)
            .setTitle("Error report (69 mod)")
            .setMessage(text.take(3500))
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("error", text))
                Toast.makeText(activity, "Copied. Paste it to Claude.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Clear") { _, _ -> f.delete() }
            .show()
    }
}
