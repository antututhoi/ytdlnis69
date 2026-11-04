package com.deniscerri.ytdl.mod69

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 69 mod: after a video download, hand the file to 69-Media Helper to make it smaller
 * as H.265 MP4 (phone video chip) or WebM (VP9). The original is kept if the result isn't smaller.
 * The choice is made per download in the download card ("Shrink" chips).
 */
object Mod69Shrink {
    // Same values as com.gor69.mediahelper.HelperContract
    private const val PACKAGE = "com.gor69.mediahelper"
    private const val ACTION_PROCESS = "com.gor69.mediahelper.PROCESS"
    private const val MSG_PROCESS = 1
    private const val MSG_RESULT = 2
    private const val KEY_IN = "in"
    private const val KEY_OUT = "out"
    private const val KEY_TYPE = "type"
    private const val KEY_EXT = "ext"
    private const val KEY_OK = "ok"
    private const val KEY_CHANGED = "changed"
    private const val KEY_ERROR = "error"
    private const val KEY_INFO = "info"
    private const val KEY_VIDEO_TARGET = "video_target"
    private const val TYPE_VIDEO = 2

    const val OFF = ""
    const val H265 = "h265"
    const val WEBM = "webm"

    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "flv", "3gp")

    private fun prefs(c: Context) = c.getSharedPreferences("mod69_shrink", Context.MODE_PRIVATE)

    /** Last choice, used as the default for the next download. */
    fun lastChoice(c: Context): String = PreferenceManager.getDefaultSharedPreferences(c).getString("mod69_shrink_last", OFF) ?: OFF

    fun setChoice(c: Context, url: String, target: String) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putString("mod69_shrink_last", target).apply()
        if (url.isBlank()) return
        prefs(c).edit().apply { if (target == OFF) remove(url) else putString(url, target) }.apply()
    }

    fun choiceFor(c: Context, url: String): String = prefs(c).getString(url, OFF) ?: OFF

    fun isInstalled(c: Context): Boolean = runCatching {
        c.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    /**
     * Shrinks the video files in [paths] one after another. Runs on the worker thread (blocking).
     * @return the new list of paths (a file may get a new extension).
     */
    fun process(c: Context, url: String, paths: List<String>, onStatus: (String) -> Unit): List<String> {
        val target = choiceFor(c, url)
        if (target == OFF) return paths
        prefs(c).edit().remove(url).apply()
        if (!isInstalled(c)) {
            onStatus("69-Media Helper is not installed – video kept as it is")
            return paths
        }
        return paths.map { p ->
            val f = File(p)
            if (!f.isFile || f.extension.lowercase() !in VIDEO_EXT) return@map p
            onStatus("Shrinking with 69-Media Helper (" + (if (target == H265) "H.265" else "WebM") + ")…")
            runCatching { shrinkOne(c, f, target, onStatus) }.getOrElse {
                onStatus("Shrink failed: ${it.message}")
                null
            } ?: p
        }
    }

    private fun shrinkOne(c: Context, file: File, target: String, onStatus: (String) -> Unit): String? {
        val authority = c.packageName + ".fileprovider"
        val out = File(c.cacheDir, "mod69_shrink_" + System.currentTimeMillis())
        out.createNewFile()
        val inUri: Uri = FileProvider.getUriForFile(c, authority, file)
        val outUri: Uri = FileProvider.getUriForFile(c, authority, out)
        c.grantUriPermission(PACKAGE, inUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.grantUriPermission(PACKAGE, outUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val thread = HandlerThread("mod69-shrink").apply { start() }
        val done = CountDownLatch(1)
        var result: Bundle? = null
        val replyTo = Messenger(Handler(thread.looper) { msg ->
            if (msg.what == MSG_RESULT) {
                result = msg.data
                done.countDown()
            }
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val m = Message.obtain(null, MSG_PROCESS)
                m.data = Bundle().apply {
                    putParcelable(KEY_IN, inUri)
                    putParcelable(KEY_OUT, outUri)
                    putInt(KEY_TYPE, TYPE_VIDEO)
                    putString(KEY_EXT, file.extension.lowercase())
                    putString(KEY_VIDEO_TARGET, target)
                }
                m.replyTo = replyTo
                runCatching { Messenger(service).send(m) }.onFailure { done.countDown() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                done.countDown()
            }
        }
        val bound = c.bindService(Intent(ACTION_PROCESS).setPackage(PACKAGE), conn, Context.BIND_AUTO_CREATE)
        try {
            if (!bound) throw IllegalStateException("cannot connect to 69-Media Helper")
            done.await(3, TimeUnit.HOURS)
        } finally {
            runCatching { c.unbindService(conn) }
            thread.quitSafely()
            c.revokeUriPermission(inUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.revokeUriPermission(outUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val r = result ?: throw IllegalStateException("no answer from 69-Media Helper")
        if (!r.getBoolean(KEY_OK)) throw IllegalStateException(r.getString(KEY_ERROR) ?: "helper error")
        r.getString(KEY_INFO)?.let { info -> onStatus(info.lines().lastOrNull().orEmpty()) }
        if (!r.getBoolean(KEY_CHANGED) || out.length() < 1024) {
            out.delete()
            return null
        }
        val ext = r.getString(KEY_EXT)?.ifBlank { null } ?: file.extension
        val dest = File(file.parentFile, file.nameWithoutExtension + "." + ext)
        out.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        out.delete()
        if (dest.absolutePath != file.absolutePath) file.delete()
        return dest.absolutePath
    }
}
