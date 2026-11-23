package com.photo_editor.my_photo_editor.repository

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * File organisation features found in the best gallery apps:
 * copy / move to album, rename, and sharing photos with their location removed.
 * All operations go through MediaStore so they work with scoped storage.
 */
class MediaOrganizer(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** An album the user can copy/move into. [relativePath] ends with '/'. */
    data class AlbumTarget(val name: String, val relativePath: String)

    /** Move and rename need the system "allow editing" prompt, available from Android 11. */
    val canEditInPlace: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Existing albums in folders MediaStore accepts photos and videos in. */
    suspend fun albumTargets(): List<AlbumTarget> = withContext(Dispatchers.IO) {
        val targets = LinkedHashMap<String, AlbumTarget>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
                try {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                        null, null,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val path = c.getString(0) ?: continue
                            if (!isWritableRoot(path) || targets.containsKey(path)) continue
                            val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: path.trimEnd('/').substringAfterLast('/')
                            targets[path] = AlbumTarget(name, path)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
                try {
                    @Suppress("DEPRECATION")
                    resolver.query(collection, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val data = c.getString(0) ?: continue
                            if (!data.startsWith(root)) continue
                            val path = data.removePrefix(root).substringBeforeLast('/', "") + "/"
                            if (!isWritableRoot(path) || targets.containsKey(path)) continue
                            targets[path] = AlbumTarget(path.trimEnd('/').substringAfterLast('/'), path)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        targets.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    /** A new album the user named. DCIM accepts both photos and videos on every Android version. */
    fun newAlbumTarget(name: String): AlbumTarget {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        return AlbumTarget(clean, "DCIM/$clean/")
    }

    /** Copies items into [target]. Returns how many were copied. Originals are untouched. */
    suspend fun copyToAlbum(items: List<MediaFile>, target: AlbumTarget): Int = withContext(Dispatchers.IO) {
        var copied = 0
        items.forEach { item ->
            val mime = item.mimeType ?: resolver.getType(item.uri) ?: if (item.type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            val kind = if (item.type == MediaType.VIDEO) MediaStoreSaver.Kind.VIDEO else MediaStoreSaver.Kind.IMAGE
            val saved = MediaStoreSaver.save(appContext, displayName(item), mime, kind, target.relativePath.trimEnd('/')) { out ->
                resolver.openInputStream(item.uri)?.use { it.copyTo(out, 64 * 1024) } != null
            }
            if (saved != null) copied++
        }
        copied
    }

    /** Android 11+: system prompt that lets the app move/rename files it didn't create. */
    fun createWriteRequest(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    /** Moves items into [target] (after [createWriteRequest] was approved). Returns moved count. */
    suspend fun moveToAlbum(uris: List<Uri>, target: AlbumTarget): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext 0
        var moved = 0
        uris.forEach { uri ->
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath) }
                if (resolver.update(uri, values, null, null) > 0) moved++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        moved
    }

    /** Renames a file keeping its extension (after [createWriteRequest] was approved). */
    suspend fun rename(uri: Uri, newBaseName: String): Boolean = withContext(Dispatchers.IO) {
        val current = queryDisplayName(uri) ?: return@withContext false
        val ext = current.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
        val base = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(ext)
        if (base.isEmpty()) return@withContext false
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, base + ext) }
            resolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun queryDisplayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Makes shareable copies of photos with GPS location (and for re-encoded files, all
     * metadata) removed. JPEGs keep full quality: only the location tags are deleted. Other
     * formats are re-encoded as high-quality JPEG. Videos are skipped (returned in [skipped]).
     */
    data class CleanCopies(val uris: List<Uri>, val skipped: Int)

    suspend fun locationFreeCopies(items: List<MediaFile>): CleanCopies = withContext(Dispatchers.IO) {
        val dir = ShareHelper.freshShareDir(appContext)
        val out = mutableListOf<Uri>()
        var skipped = 0
        items.forEachIndexed { index, item ->
            if (item.type != MediaType.IMAGE) { skipped++; return@forEachIndexed }
            val mime = item.mimeType ?: resolver.getType(item.uri)
            val base = displayName(item).substringBeforeLast('.').ifBlank { "photo_$index" }
            val file = try {
                if (mime == "image/jpeg") stripJpegLocation(item.uri, File(dir, uniqueName(dir, "$base.jpg")))
                else reencode(item.uri, File(dir, uniqueName(dir, "$base.jpg")))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            if (file != null) out.add(ShareHelper.fileUri(appContext, file)) else skipped++
        }
        CleanCopies(out, skipped)
    }

    private fun stripJpegLocation(source: Uri, file: File): File? {
        resolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        return try {
            val exif = ExifInterface(file.absolutePath)
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            // Make sure the location is really gone; otherwise fall back to a clean re-encode.
            val check = ExifInterface(file.absolutePath)
            if (check.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null ||
                check.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null
            ) reencode(source, file) else file
        } catch (e: Exception) {
            reencode(source, file)
        }
    }

    private fun reencode(source: Uri, file: File): File? {
        val bitmap = BitmapLoader.decodeUpright(appContext, source, 4096) ?: return null
        return try {
            val ok = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (ok) file else null
        } finally {
            bitmap.recycle()
        }
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (File(dir, "$base ($i).$ext").exists()) i++
        return "$base ($i).$ext"
    }

    private fun displayName(item: MediaFile): String =
        item.name.takeIf { it.isNotBlank() && it != "Unknown" } ?: queryDisplayName(item.uri) ?: "media_${System.currentTimeMillis()}"

    private fun isWritableRoot(relativePath: String): Boolean {
        val root = relativePath.substringBefore('/').lowercase(Locale.ROOT)
        return root == "dcim" || root == "pictures" || root == "movies"
    }

    private companion object {
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION
        )
    }
}
