package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val contentResolver = application.contentResolver
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Debounce refreshes to avoid spamming the UI
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch {
                delay(500) // Wait for 500ms of quiet before refreshing
                loadMedia()
            }
        }
    }

    init {
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun onCleared() {
        super.onCleared()
        contentResolver.unregisterContentObserver(observer)
    }

    private val _allMedia = MutableStateFlow<List<MediaFile>>(emptyList())
    val allMedia: StateFlow<List<MediaFile>> = _allMedia.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadMedia() {
        if (_isLoading.value) return
        _isLoading.value = true
        
        viewModelScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                val fetched = fetchMedia()
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                fetched.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                fetched
            }
            _allMedia.value = mediaList
            _isLoading.value = false
        }
    }

    private fun fetchMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list.sortedWith(
            compareByDescending<MediaFile> { it.dateAdded }
                .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
        )
    }

    private fun queryMedia(uri: android.net.Uri, type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE
        )
        if (type == MediaType.VIDEO) {
            projection.add(MediaStore.Video.Media.DURATION)
        }

        try {
            contentResolver.query(
                uri,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumColumn = cursor.getColumnIndex("bucket_display_name")
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val durationColumn = if (type == MediaType.VIDEO) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (cursor.moveToNext()) {
                    val size = if (sizeColumn != -1) cursor.getString(sizeColumn) else null
                    if (size == "0") continue

                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val album = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Camera"
                    } else {
                        "Camera"
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    if (type == MediaType.VIDEO && duration == 0L) continue

                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaFile(contentUri, name, album, date, type, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
