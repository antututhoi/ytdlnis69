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

    /** @return the intent addressed to MiXplorer when enabled and installed, otherwise the same intent. */
    fun prepare(context: Context, intent: Intent): Intent {
        if (intent.action !in ACTIONS || !isEnabled(context)) return intent
        for (pkg in PACKAGES) {
            if (!isResolvable(context, pkg)) continue
            val copy = Intent(intent).setPackage(pkg)
            if (copy.resolveActivity(context.packageManager) != null) return copy
        }
        return intent
    }

    /** Replacement for ActivityResultContracts.StartActivityForResult() at picker launch sites. */
    class Contract : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = prepare(context, input)

        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, intent)
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
    }

    /** File picker contract for opening one document. */
    class OpenOne : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            prepare(context, super.createIntent(context, input))
    }
}
