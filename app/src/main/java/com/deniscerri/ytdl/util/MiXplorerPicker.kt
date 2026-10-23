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

    @Volatile
    private var appContext: Context? = null

    /** Storage roots on this phone, e.g. /storage/emulated/0 and /storage/3035-4269. */
    private fun storageRoots(context: Context): List<String> {
        val roots = mutableListOf<String>()
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val p = dir.absolutePath
            val i = p.indexOf("/Android/")
            if (i > 0) roots.add(p.substring(0, i))
        }
        runCatching {
            java.io.File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "self" && f.name != "emulated") roots.add(f.absolutePath)
            }
        }
        if (roots.none { it.startsWith("/storage/emulated/") }) roots.add(0, "/storage/emulated/0")
        return roots.distinct()
    }

    /**
     * MiXplorer returns its own address, e.g. content://com.mixplorer.silver.file/515455478!!/DL/x.
     * Turn it into a real folder path like /storage/3035-4269/DL/x.
     */
    private fun mixplorerUriToPath(context: Context, uri: Uri): String? {
        // 1. Ask MiXplorer for the real path.
        runCatching {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank() && v.startsWith("/")) return v.trimEnd('/')
                }
            }
        }
        // 2. Take the part after "!!" and find which storage holds that folder.
        val decoded = Uri.decode(uri.encodedPath ?: return null)
        val marker = decoded.indexOf("!!")
        val relative = (if (marker >= 0) decoded.substring(marker + 2) else decoded).trim('/')
        if (relative.isEmpty()) return null
        if (relative.startsWith("storage/")) return "/" + relative
        for (root in storageRoots(context)) {
            val f = java.io.File(root, relative)
            if (f.isDirectory) return f.absolutePath
        }
        return null
    }

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
        appContext = context.applicationContext
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
            "content" -> if (uri.authority.orEmpty().startsWith("com.mixplorer")) {
                appContext?.let { mixplorerUriToPath(it, uri) }
            } else null
            else -> null
        }
        if (path == null && uri.authority.orEmpty().startsWith("com.mixplorer")) {
            appContext?.let { toast(it, "Could not read the folder from MiXplorer: $uri") }
            return null
        }
        return if (path != null) Intent().setData(Uri.parse(path.trimEnd('/'))) else data
    }

    /** Replacement for ActivityResultContracts.StartActivityForResult() at picker launch sites. */
    class Contract : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = prepare(context, input)

        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            normalize(intent).let { fixed ->
                // A MiXplorer answer that could not be read counts as "cancelled", so nothing breaks.
                if (intent != null && fixed == null) ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                else ActivityResult(resultCode, fixed)
            }
    }

    /** Folder picker contract that keeps the grant across restarts. */
    class PersistableTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            prepare(
                context,
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
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
