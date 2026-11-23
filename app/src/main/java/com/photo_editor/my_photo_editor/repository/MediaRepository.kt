package com.photo_editor.my_photo_editor.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.annotation.RequiresApi
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.db.FavoriteMedia
import com.photo_editor.my_photo_editor.db.HiddenMedia
import com.photo_editor.my_photo_editor.model.MediaDetails
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.TrashedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single place for gallery data operations that are shared between screens:
 * favorites, Locked Folder (hidden media), Recycle Bin and detailed metadata.
 * ViewModels call into this class; Activities/Fragments never talk to MediaStore
 * for these features directly.
 */
class MediaRepository(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val db = AppDatabase.getDatabase(appContext)

    // ---------------------------------------------------------------- favorites

    suspend fun setFavorite(uris: List<Uri>, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        if (favorite) {
            db.favoriteDao().addFavorites(uris.map { FavoriteMedia(it.toString()) })
        } else {
            db.favoriteDao().removeFavorites(uris.map { it.toString() })
        }
    }

    // ------------------------------------------------------------ locked folder

    suspend fun hide(uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        db.hiddenDao().hide(uris.map { HiddenMedia(it.toString()) })
    }

    suspend fun unhide(uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        db.hiddenDao().unhide(uris.map { it.toString() })
    }

    suspend fun getHiddenUris(): Set<String> = withContext(Dispatchers.IO) {
        db.hiddenDao().getHiddenUris().toSet()
    }

    /** Resolves hidden uris to real media. Entries whose file was removed are cleaned up. */
    suspend fun loadHiddenMedia(): List<MediaFile> = withContext(Dispatchers.IO) {
        val hidden = db.hiddenDao().getHiddenUris().toSet()
        if (hidden.isEmpty()) return@withContext emptyList()
        val all = queryAll()
        val found = all.filter { hidden.contains(it.uri.toString()) }
        val missing = hidden - found.map { it.uri.toString() }.toSet()
        if (missing.isNotEmpty()) db.hiddenDao().unhide(missing.toList())
        found
    }

    // ------------------------------------------------------------- recycle bin

    /** Android 11+ only: moves media to the system Recycle Bin (restorable for ~30 days). */
    fun createTrashRequest(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return MediaStore.createTrashRequest(contentResolver, uris, true).intentSender
    }

    fun createRestoreRequest(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return MediaStore.createTrashRequest(contentResolver, uris, false).intentSender
    }

    fun createDeleteRequest(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(contentResolver, uris).intentSender
    }

    /** Pre Android 11 fallback: permanent delete. Returns number of deleted rows. */
    suspend fun deleteLegacy(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        uris.forEach { uri ->
            try {
                count += contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        count
    }

    val isRecycleBinSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun loadTrashedMedia(): List<TrashedMedia> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val list = mutableListOf<TrashedMedia>()
        list.addAll(queryTrashed(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryTrashed(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        list.sortedBy { it.expiresAtSeconds }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryTrashed(collection: Uri, type: MediaType): List<TrashedMedia> {
        val list = mutableListOf<TrashedMedia>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_EXPIRES)
            )
        }
        try {
            contentResolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val expCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                @Suppress("DEPRECATION")
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = (c.getString(nameCol) ?: "Unknown").removeTrashPrefix()
                    val media = MediaFile(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        album = if (albumCol != -1) c.getString(albumCol) ?: "Camera" else "Camera",
                        dateAdded = c.getLong(addedCol),
                        type = type,
                        duration = if (durCol != -1) c.getLong(durCol) else 0L,
                        size = if (sizeCol != -1) c.getLong(sizeCol) else 0L,
                        mimeType = if (mimeCol != -1) c.getString(mimeCol) else null
                    )
                    list.add(
                        TrashedMedia(
                            media = media,
                            expiresAtSeconds = if (expCol != -1) c.getLong(expCol) else 0L,
                            dataPath = if (dataCol != -1) c.getString(dataCol) else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /** MediaStore renames trashed files to ".trashed-<time>-name". Show the original name. */
    private fun String.removeTrashPrefix(): String =
        if (startsWith(".trashed-")) substringAfter('-').substringAfter('-') else this

    // ------------------------------------------------------------------ queries

    /** Full library query including extended metadata. */
    fun queryAll(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list.sortedByDescending { it.dateAdded }
    }

    private fun queryCollection(collection: Uri, type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            "width",
            "height",
            "datetaken"
        )
        if (type == MediaType.VIDEO) projection.add(MediaStore.Video.Media.DURATION)
        try {
            contentResolver.query(
                collection, projection.toTypedArray(), null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumCol = c.getColumnIndex("bucket_display_name")
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val wCol = c.getColumnIndex("width")
                val hCol = c.getColumnIndex("height")
                val takenCol = c.getColumnIndex("datetaken")
                val durCol = if (type == MediaType.VIDEO) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    list.add(
                        MediaFile(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "Unknown",
                            album = if (albumCol != -1) c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Camera" else "Camera",
                            dateAdded = c.getLong(dateCol),
                            type = type,
                            duration = if (durCol != -1) c.getLong(durCol) else 0L,
                            size = if (sizeCol != -1) c.getLong(sizeCol) else 0L,
                            width = if (wCol != -1) c.getInt(wCol) else 0,
                            height = if (hCol != -1) c.getInt(hCol) else 0,
                            mimeType = if (mimeCol != -1) c.getString(mimeCol) else null,
                            dateTaken = if (takenCol != -1) c.getLong(takenCol) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // ------------------------------------------------------------------ details

    suspend fun loadDetails(uri: Uri, isVideo: Boolean): MediaDetails = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment ?: "Unknown"
        var size = 0L
        var width = 0
        var height = 0
        var mime: String? = null
        var path: String? = null
        var taken = 0L
        var added = 0L
        var duration = 0L

        val projection = mutableListOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            "width", "height", "datetaken"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            projection.add(MediaStore.MediaColumns.DATA)
        }
        if (isVideo) projection.add(MediaStore.Video.Media.DURATION)

        try {
            contentResolver.query(uri, projection.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun str(col: String) = c.getColumnIndex(col).takeIf { it != -1 }?.let { c.getString(it) }
                    fun lng(col: String) = c.getColumnIndex(col).takeIf { it != -1 }?.let { c.getLong(it) } ?: 0L
                    name = str(MediaStore.MediaColumns.DISPLAY_NAME) ?: name
                    size = lng(MediaStore.MediaColumns.SIZE)
                    mime = str(MediaStore.MediaColumns.MIME_TYPE)
                    width = lng("width").toInt()
                    height = lng("height").toInt()
                    taken = lng("datetaken")
                    added = lng(MediaStore.MediaColumns.DATE_ADDED)
                    duration = if (isVideo) lng(MediaStore.Video.Media.DURATION) else 0L
                    path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        str(MediaStore.MediaColumns.RELATIVE_PATH)?.let { "/$it$name" }
                    } else {
                        @Suppress("DEPRECATION")
                        str(MediaStore.MediaColumns.DATA)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        var camera: String? = null
        var exposure: String? = null
        var lat: Double? = null
        var lng: Double? = null
        if (!isVideo) {
            try {
                val exifUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
                } else uri
                val stream = try {
                    contentResolver.openInputStream(exifUri)
                } catch (_: SecurityException) {
                    contentResolver.openInputStream(uri) // no ACCESS_MEDIA_LOCATION: location is redacted
                }
                stream?.use { input ->
                    val exif = ExifInterface(input)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    camera = when {
                        model.isNullOrBlank() -> make
                        make.isNullOrBlank() || model.startsWith(make, ignoreCase = true) -> model
                        else -> "$make $model"
                    }
                    val parts = mutableListOf<String>()
                    exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { parts.add("ƒ/%.1f".format(Locale.US, it)) }
                    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
                        parts.add(if (it >= 1.0) "%.1fs".format(Locale.US, it) else "1/${(1.0 / it).toInt()}")
                    }
                    exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { fl ->
                        val v = fl.split("/").let { p -> if (p.size == 2) p[0].toDoubleOrNull()?.div(p[1].toDoubleOrNull() ?: 1.0) else fl.toDoubleOrNull() }
                        v?.let { parts.add("%.1fmm".format(Locale.US, it)) }
                    }
                    @Suppress("DEPRECATION")
                    exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { parts.add("ISO $it") }
                    exposure = parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
                    val latLong = FloatArray(2)
                    @Suppress("DEPRECATION")
                    if (exif.getLatLong(latLong) && !(latLong[0] == 0f && latLong[1] == 0f)) {
                        lat = latLong[0].toDouble()
                        lng = latLong[1].toDouble()
                    }
                    if (width == 0 || height == 0) {
                        width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val whenMs = if (taken > 0) taken else added * 1000L
        val date = if (whenMs > 0) Date(whenMs) else null
        MediaDetails(
            name = name,
            dateText = date?.let { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(it) },
            timeText = date?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(it) },
            resolution = if (width > 0 && height > 0) "$width × $height" else null,
            megapixels = if (!isVideo && width > 0 && height > 0) "%.1f MP".format(Locale.US, width * height / 1_000_000f) else null,
            sizeText = if (size > 0) Formatter.formatFileSize(appContext, size) else null,
            mimeType = mime,
            path = path,
            durationText = if (isVideo && duration > 0) formatDuration(duration) else null,
            camera = camera,
            exposure = exposure,
            latitude = lat,
            longitude = lng
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Builds a share intent for one or many media items. */
    fun buildShareIntent(items: List<MediaFile>): Intent? {
        if (items.isEmpty()) return null
        val allVideos = items.all { it.type == MediaType.VIDEO }
        val allImages = items.all { it.type == MediaType.IMAGE }
        val mime = when {
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, items[0].uri) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            }
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, if (items.size == 1) "Share" else "Share ${items.size} items")
    }

    companion object {
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
