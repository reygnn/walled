package com.github.reygnn.walled.data

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The layer images, copied into app storage ([dir]).
 *
 * Why copy: a picker `content://` URI loses its grant after the process dies or
 * the source app updates; a private copy survives both and needs no persistable
 * permission. The state stores the bare file name, never a path.
 *
 * Plain `java.io` on purpose (blocking — call it off Main), so it is
 * JVM-testable against a temp directory without Robolectric.
 */
class LayerImageStore(private val dir: File) {

    companion object {
        private const val TAG = "LayerImageStore"
        private const val PREFIX = "layer_"

        /**
         * Orphan GC leaves files younger than this alone: a copy that is still
         * being made, or one whose state save has not landed yet, is not an orphan.
         */
        const val ORPHAN_MIN_AGE_MS = 60_000L

        // Collision-free names for concurrent copies within one millisecond.
        private val counter = AtomicLong(0)
    }

    /** Copies [source] in and returns the new file name, or `null` on failure. */
    fun copyIn(source: () -> InputStream?): String? {
        dir.mkdirs()
        val name = "$PREFIX${System.currentTimeMillis()}_${counter.getAndIncrement()}"
        val dest = File(dir, name)
        return try {
            val copied = source()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (copied == null) {
                Log.w(TAG, "Could not open the source image")
                null
            } else {
                name
            }
        } catch (e: IOException) {
            Log.w(TAG, "Copying the source image failed", e)
            dest.delete()
            null
        }
    }

    /** A fresh stream for [fileName], or `null` if it is gone. */
    fun open(fileName: String): InputStream? = fileFor(fileName)?.takeIf { it.isFile }?.inputStream()

    /** Size of [fileName] in bytes, or `null` if it is gone. */
    fun length(fileName: String): Long? = fileFor(fileName)?.takeIf { it.isFile }?.length()

    /** The first [size] bytes of [fileName] (fewer for a short file), or `null` if it is gone. */
    fun header(fileName: String, size: Int = 16): ByteArray? = try {
        open(fileName)?.use { it.readNBytes(size) }
    } catch (e: IOException) {
        Log.w(TAG, "Reading the header of $fileName failed", e)
        null
    }

    fun delete(fileNames: Collection<String>) {
        fileNames.forEach { name -> fileFor(name)?.delete() }
    }

    /**
     * Deletes every file in [dir] not in [referenced] and older than
     * [ORPHAN_MIN_AGE_MS] at [nowMs]. Leftovers of a removed layer whose delete
     * did not run (process killed in between) end up here.
     */
    fun deleteOrphans(referenced: Set<String>, nowMs: Long = System.currentTimeMillis()): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile || file.name in referenced) continue
            if (nowMs - file.lastModified() < ORPHAN_MIN_AGE_MS) continue
            if (file.delete()) deleted++
        }
        return deleted
    }

    /** `null` for a name that would escape [dir] (corrupt state) — never touch such a path. */
    private fun fileFor(fileName: String): File? {
        if (fileName.isEmpty() || '/' in fileName || fileName == "." || fileName == "..") return null
        return File(dir, fileName)
    }
}
