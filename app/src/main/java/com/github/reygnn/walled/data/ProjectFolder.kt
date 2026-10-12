package com.github.reygnn.walled.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream

/**
 * The folder holding project JSONs and their images. Blocking — call off Main.
 * Names are plain file names directly inside the folder.
 */
interface ProjectFolder {
    /** Shown in the menu (the folder's own name). */
    val displayName: String

    /** The project files in the folder, sorted by name. */
    fun listProjects(): List<String>

    fun readText(name: String): String?

    fun openFile(name: String): InputStream?

    /** Size of [name] in bytes, or `null` when the folder has no such file. */
    fun length(name: String): Long?

    /** Creates or overwrites [name] with [text]; false on failure. */
    fun writeText(name: String, text: String): Boolean

    /** Creates or overwrites [name] with the bytes of [source]; false on failure. */
    fun writeFile(name: String, source: () -> InputStream?): Boolean
}

/**
 * [ProjectFolder] on a Storage Access Framework tree the user picked. A single
 * picked file gives no access to its sibling images — only a granted folder makes
 * the project's relative file names resolvable.
 */
class SafProjectFolder private constructor(
    private val context: Context,
    private val root: DocumentFile,
) : ProjectFolder {

    companion object {
        private const val TAG = "SafProjectFolder"

        /**
         * The folder behind [treeUri], or `null` when its persisted grant is gone
         * (folder deleted, permission revoked) — the caller then asks again.
         */
        fun open(context: Context, treeUri: String): ProjectFolder? {
            val uri = treeUri.toUri()
            val granted = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            if (!granted) return null
            val root = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory } ?: return null
            return SafProjectFolder(context.applicationContext, root)
        }

        /** Keeps read+write access to [treeUri] across restarts. */
        fun persistGrant(context: Context, treeUri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    override val displayName: String get() = root.name ?: "?"

    override fun listProjects(): List<String> =
        root.listFiles()
            .filter { it.isFile }
            .mapNotNull { it.name }
            .filter { it.endsWith(".json", ignoreCase = true) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun find(name: String): DocumentFile? = root.findFile(name)?.takeIf { it.isFile }

    override fun readText(name: String): String? = try {
        openFile(name)?.use { it.readBytes().decodeToString() }
    } catch (e: IOException) {
        Log.w(TAG, "Reading $name failed", e)
        null
    }

    override fun openFile(name: String): InputStream? =
        find(name)?.let { context.contentResolver.openInputStream(it.uri) }

    override fun length(name: String): Long? = find(name)?.length()

    override fun writeText(name: String, text: String): Boolean =
        writeFile(name) { text.byteInputStream() }

    override fun writeFile(name: String, source: () -> InputStream?): Boolean {
        val target = find(name) ?: root.createFile(mimeTypeOf(name), name) ?: return false
        return try {
            // "wt": truncate an existing file instead of leaving its tail behind.
            val copied = context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                source()?.use { input -> input.copyTo(out) }
            }
            copied != null
        } catch (e: IOException) {
            Log.w(TAG, "Writing $name failed", e)
            false
        }
    }

    /**
     * The MIME type for a new document. It must match the extension, or the
     * provider appends one of its own (`ducks.png` → `ducks.png.bin`).
     */
    private fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
