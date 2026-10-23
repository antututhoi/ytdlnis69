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

    private enum class Route { NONE, TREE, FOLDER_RESOURCE, OI_DIRECTORY, DOCUMENT }

    @Volatile
    private var lastRoute = Route.NONE

    @Volatile
    private var appContext: Context? = null

    private fun findPackage(context: Context, build: (String) -> Intent): Intent? {
        for (pkg in PACKAGES) {
            if (!isResolvable(context, pkg)) continue
            val intent = build(pkg)
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return null
    }

    private fun toast(context: Context, text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * @return the intent addressed to MiXplorer when enabled and installed, otherwise the same intent.
     * MiXplorer may not answer the system folder intent, so the older folder-picking intents are tried too.
     */
    fun prepare(context: Context, intent: Intent): Intent {
        lastRoute = Route.NONE
        appContext = context.applicationContext
        if (intent.action !in ACTIONS || !isEnabled(context)) return intent
        if (!isInstalled(context)) return intent

        // 1. The same intent, addressed to MiXplorer.
        findPackage(context) { pkg -> Intent(intent).setPackage(pkg) }?.let {
            lastRoute = if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) Route.TREE else Route.DOCUMENT
            toast(context, "MiXplorer")
            return it
        }

        if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) {
            // 2. Folder picking the way file managers have long offered it.
            findPackage(context) { pkg ->
                Intent(Intent.ACTION_GET_CONTENT).setType("resource/folder").setPackage(pkg)
            }?.let {
                lastRoute = Route.FOLDER_RESOURCE
                toast(context, "MiXplorer (folder mode)")
                return it
            }
            findPackage(context) { pkg ->
                Intent("org.openintents.action.PICK_DIRECTORY")
                    .setData(Uri.parse("file://" + android.os.Environment.getExternalStorageDirectory().absolutePath))
                    .setPackage(pkg)
            }?.let {
                lastRoute = Route.OI_DIRECTORY
                toast(context, "MiXplorer (folder mode 2)")
                return it
            }
            toast(context, "MiXplorer cannot choose folders here, using the system chooser")
        }
        return intent
    }

    /** A folder chosen in the older ways comes back as a path, which this app accepts as a folder. */
    private fun normalize(resultCode: Int, data: Intent?): Intent? {
        val route = lastRoute
        lastRoute = Route.NONE
        if (route != Route.NONE) {
            appContext?.let { toast(it, "MiXplorer result: code=$resultCode data=${data?.data} extras=${data?.extras?.keySet()}") }
        }
        if (data == null || (route != Route.FOLDER_RESOURCE && route != Route.OI_DIRECTORY)) return data
        val uri = data.data ?: return data
        val path = when (uri.scheme) {
            "file" -> uri.path
            null -> uri.path
            else -> null
        }
        return if (path != null) Intent().setData(Uri.parse(path.trimEnd('/'))) else data
    }

    /** Replacement for ActivityResultContracts.StartActivityForResult() at picker launch sites. */
    class Contract : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = prepare(context, input)

        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, normalize(resultCode, intent))
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
