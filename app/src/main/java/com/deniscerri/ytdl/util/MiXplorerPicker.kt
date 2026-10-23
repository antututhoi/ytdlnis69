package com.deniscerri.ytdl.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager

/**
 * Opens the folder/file chooser in MiXplorer instead of the Android system picker (SAF).
 * MiXplorer answers the standard OPEN_DOCUMENT_TREE / OPEN_DOCUMENT / CREATE_DOCUMENT intents,
 * so the intent is only addressed to its package. No start location is passed, so MiXplorer
 * opens where it was used last. If MiXplorer is not installed (or the setting is off),
 * the system picker is used as before.
 */
object MiXplorerPicker {
    const val PREF_KEY = "use_mixplorer_picker"

    private val PACKAGES = listOf("com.mixplorer.silver", "com.mixplorer")
    private val ACTIONS = setOf(
        Intent.ACTION_OPEN_DOCUMENT_TREE,
        Intent.ACTION_OPEN_DOCUMENT,
        Intent.ACTION_CREATE_DOCUMENT
    )

    fun isInstalled(context: Context): Boolean = PACKAGES.any { isResolvable(context, it) }

    private fun isResolvable(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, true)

    private const val BROWSE = "com.mixplorer.activities.BrowseActivity"
    private const val PICK_DIRECTORY = "org.openintents.action.PICK_DIRECTORY"

    @Volatile
    private var pickingFolder = false

    private fun toast(context: Context, text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Opens MiXplorer's own screen directly.
     * Folders use MiXplorer's "pick directory" mode; files use the normal open/create request.
     * No start folder is passed, so MiXplorer opens where it was used last.
     * (An earlier try passed a file:// start folder, which Android blocks and made the app restart.)
     */
    fun prepare(context: Context, intent: Intent): Intent {
        pickingFolder = false
        if (intent.action !in ACTIONS || !isEnabled(context)) return intent
        val pkg = PACKAGES.firstOrNull { pkg ->
            runCatching {
                context.packageManager.getActivityInfo(android.content.ComponentName(pkg, BROWSE), 0)
                true
            }.getOrDefault(false)
        } ?: return intent

        if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) {
            pickingFolder = true
            return Intent(PICK_DIRECTORY).setClassName(pkg, BROWSE)
        }
        return Intent(intent).setClassName(pkg, BROWSE)
    }

    /** MiXplorer answers a folder pick with a file path; the app accepts a plain /storage/... path as a folder. */
    private fun normalize(data: Intent?): Intent? {
        val folder = pickingFolder
        pickingFolder = false
        if (!folder || data == null) return data
        val uri = data.data ?: return data
        val path = when (uri.scheme) {
            "file", null, "folder", "directory" -> uri.path
            else -> null
        }
        return if (path != null) Intent().setData(Uri.parse(path.trimEnd('/'))) else data
    }

    /** Replacement for ActivityResultContracts.StartActivityForResult() at picker launch sites. */
    class Contract : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = prepare(context, input)

        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, normalize(intent))
    }

    /** Folder picker contract that keeps the grant across restarts. */
    class PersistableTree : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            prepare(
                context,
                super.createIntent(context, null).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            )

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) normalize(intent)?.data else null
    }

    /** File picker contract for opening one document. */
    class OpenOne : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            prepare(context, super.createIntent(context, input))
    }
}
