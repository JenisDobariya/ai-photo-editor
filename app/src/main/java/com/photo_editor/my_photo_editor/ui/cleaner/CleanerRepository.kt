package com.photo_editor.my_photo_editor.ui.cleaner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the photo / video library for the storage cleaner (Locked Folder items excluded). */
class CleanerRepository(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    suspend fun loadLibrary(): List<CleanerMedia> = withContext(Dispatchers.IO) {
        val hidden = try {
            AppDatabase.getDatabase(appContext).hiddenDao().getHiddenUris().toHashSet()
        } catch (e: Exception) {
            e.printStackTrace()
            HashSet<String>()
        }
        val list = ArrayList<CleanerMedia>()
        list.addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false))
        list.addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true))
        if (hidden.isEmpty()) list else list.filterNot { hidden.contains(it.uri.toString()) }
    }

    /**
     * Makes sure the background analysis covers the library (no-op when it is already up to
     * date or running). Passes the same visible library (hidden items excluded) as the gallery.
     */
    fun syncIndex(library: List<CleanerMedia>) {
        val media = library.asSequence()
            .filter { !it.isVideo }
            .map {
                MediaFile(
                    uri = it.uri,
                    name = it.name,
                    album = it.album,
                    dateAdded = it.dateAddedSec,
                    type = MediaType.IMAGE,
                    size = it.size,
                    width = it.width,
                    height = it.height,
                    mimeType = it.mimeType,
                    dateTaken = it.dateTakenMs,
                    relativePath = it.relativePath
                )
            }
            .toList()
        SmartIndexRepository.sync(appContext, media)
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean): List<CleanerMedia> {
        val list = ArrayList<CleanerMedia>()
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            COL_BUCKET,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            COL_WIDTH,
            COL_HEIGHT,
            COL_DATE_TAKEN,
            if (scoped) COL_RELATIVE_PATH else COL_DATA
        )
        if (isVideo) projection.add(COL_DURATION)
        try {
            contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumCol = c.getColumnIndex(COL_BUCKET)
                val addedCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val wCol = c.getColumnIndex(COL_WIDTH)
                val hCol = c.getColumnIndex(COL_HEIGHT)
                val takenCol = c.getColumnIndex(COL_DATE_TAKEN)
                val pathCol = c.getColumnIndex(if (scoped) COL_RELATIVE_PATH else COL_DATA)
                val durCol = if (isVideo) c.getColumnIndex(COL_DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawPath = if (pathCol != -1) c.getString(pathCol) else null
                    // Pre-Android 10 we get the absolute file path; keep only its folder.
                    val folder = if (scoped) rawPath else rawPath?.substringBeforeLast('/', rawPath)
                    list.add(
                        CleanerMedia(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = (if (nameCol != -1) c.getString(nameCol) else null) ?: "Unknown",
                            isVideo = isVideo,
                            size = if (sizeCol != -1) c.getLong(sizeCol) else 0L,
                            width = if (wCol != -1) c.getInt(wCol) else 0,
                            height = if (hCol != -1) c.getInt(hCol) else 0,
                            dateAddedSec = if (addedCol != -1) c.getLong(addedCol) else 0L,
                            dateTakenMs = if (takenCol != -1) c.getLong(takenCol) else 0L,
                            album = (if (albumCol != -1) c.getString(albumCol) else null)?.takeIf { it.isNotBlank() } ?: "Camera",
                            relativePath = folder,
                            mimeType = if (mimeCol != -1) c.getString(mimeCol) else null,
                            durationMs = if (durCol != -1) c.getLong(durCol) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private companion object {
        const val COL_BUCKET = "bucket_display_name"
        const val COL_WIDTH = "width"
        const val COL_HEIGHT = "height"
        const val COL_DATE_TAKEN = "datetaken"
        const val COL_RELATIVE_PATH = "relative_path"
        const val COL_DATA = "_data"
        const val COL_DURATION = "duration"
    }
}
