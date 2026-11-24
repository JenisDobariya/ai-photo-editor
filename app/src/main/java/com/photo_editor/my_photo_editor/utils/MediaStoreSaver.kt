package com.photo_editor.my_photo_editor.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock

import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves new files (photos, GIFs, videos, PDFs) into the shared storage so they show up in
 * the gallery / Files app. Handles Android 10+ (MediaStore + IS_PENDING) and older versions
 * (public folder + media scan). A failed write never leaves a broken or hidden file behind.
 */
object MediaStoreSaver {

    /** Where edited photos already go, so every tool's output lands in one album. */
    const val PHOTOS_DIR = "Pictures/PhotoEditor"
    const val VIDEOS_DIR = "Movies/PhotoEditor"
    const val DOCUMENTS_DIR = "Documents/PhotoEditor"

    enum class Kind { IMAGE, VIDEO, DOCUMENT }

    /**
     * Creates [displayName] in [relativeDir] and lets [write] fill it.
     * [write] returns false (or throws) to signal failure. Returns the new content Uri or null.
     */
    fun save(
        context: Context,
        displayName: String,
        mimeType: String,
        kind: Kind,
        relativeDir: String = defaultDir(kind),
        write: (OutputStream) -> Boolean
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, displayName, mimeType, kind, relativeDir, write)
        } else {
            saveLegacy(context, displayName, mimeType, kind, relativeDir, write)
        }
    }

    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        namePrefix: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 95,
        relativeDir: String = PHOTOS_DIR
    ): Uri? {
        val (ext, mime) = when (format) {
            Bitmap.CompressFormat.PNG -> "png" to "image/png"
            Bitmap.CompressFormat.JPEG -> "jpg" to "image/jpeg"
            else -> "webp" to "image/webp"
        }
        return save(context, "${namePrefix}_${System.currentTimeMillis()}.$ext", mime, Kind.IMAGE, relativeDir) { out ->
            bitmap.compress(format, quality, out)
        }
    }

    /** Copies a local file (e.g. an export in the cache folder) into shared storage. */
    fun saveFile(context: Context, source: File, displayName: String, mimeType: String, kind: Kind, relativeDir: String = defaultDir(kind)): Uri? =
        save(context, displayName, mimeType, kind, relativeDir) { out ->
            source.inputStream().use { it.copyTo(out) }
            true
        }

    fun defaultDir(kind: Kind) = when (kind) {
        Kind.IMAGE -> PHOTOS_DIR
        Kind.VIDEO -> VIDEOS_DIR
        Kind.DOCUMENT -> DOCUMENTS_DIR
    }

    private fun collectionFor(kind: Kind): Uri = when (kind) {
        Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Kind.DOCUMENT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun saveScoped(
        context: Context,
        displayName: String,
        mimeType: String,
        kind: Kind,
        relativeDir: String,
        write: (OutputStream) -> Boolean
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = try {
            resolver.insert(collectionFor(kind), values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return null
        return try {
            val ok = resolver.openOutputStream(uri)?.use { write(it) } ?: false
            if (!ok) throw java.io.IOException("Write failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        } catch (e: OutOfMemoryError) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        displayName: String,
        mimeType: String,
        kind: Kind,
        relativeDir: String,
        write: (OutputStream) -> Boolean
    ): Uri? {
        val dir = File(Environment.getExternalStorageDirectory(), relativeDir)
        if (!dir.exists() && !dir.mkdirs()) return null
        // Never write over an existing file: FileOutputStream would truncate it (e.g. "Copy to
        // album" with a file of the same name already there). Android 10+ MediaStore renames
        // clashes itself; here we pick "name (1).ext", "name (2).ext"… and claim it atomically.
        val file = claimUniqueFile(dir, displayName) ?: return null
        return try {
            val ok = FileOutputStream(file).use { write(it) }
            if (!ok) throw java.io.IOException("Write failed")
            // Only ever hand out a content:// Uri: a file:// Uri crashes (FileUriExposedException)
            // as soon as it is shared with another app.
            scanForContentUri(context, file, mimeType) ?: queryContentUri(context, file, kind)
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            null
        } catch (e: OutOfMemoryError) {
            file.delete()
            null
        }
    }

    /** First free "name.ext" / "name (n).ext" in [dir], created empty so nobody else takes it. */
    private fun claimUniqueFile(dir: File, displayName: String): File? {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var candidate = File(dir, displayName)
        var i = 1
        while (true) {
            try {
                if (candidate.createNewFile()) return candidate
            } catch (e: java.io.IOException) {
                e.printStackTrace()
                return null
            }
            if (i > MAX_NAME_ATTEMPTS) return null
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
    }

    /** Waits (up to [SCAN_TIMEOUT_MS]) for the media scanner to index [file]. */
    private fun scanForContentUri(context: Context, file: File, mimeType: String): Uri? {
        var scanned: Uri? = null
        var done = false
        val lock = Object()
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
            synchronized(lock) { scanned = uri; done = true; lock.notifyAll() }
        }
        val deadline = SystemClock.uptimeMillis() + SCAN_TIMEOUT_MS
        synchronized(lock) {
            while (!done) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                try { lock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
        return scanned?.takeIf { it.scheme == "content" }
    }

    /** The scan didn't report back in time: look the file up in MediaStore by its path. */
    @Suppress("DEPRECATION")
    private fun queryContentUri(context: Context, file: File, kind: Kind): Uri? {
        val collection = collectionFor(kind)
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(file.absolutePath),
                null
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private const val SCAN_TIMEOUT_MS = 8000L
    private const val MAX_NAME_ATTEMPTS = 9999
}
