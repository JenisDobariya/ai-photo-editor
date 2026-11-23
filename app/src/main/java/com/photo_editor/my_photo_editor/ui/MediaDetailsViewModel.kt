package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.model.MediaDetails
import com.photo_editor.my_photo_editor.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads file + EXIF metadata for the info bottom sheet. */
class MediaDetailsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private var loadedUri: Uri? = null

    fun load(uri: Uri, isVideo: Boolean) {
        if (loadedUri == uri && _details.value != null) return
        loadedUri = uri
        _details.value = null
        viewModelScope.launch {
            _details.value = repository.loadDetails(uri, isVideo)
        }
    }
}
