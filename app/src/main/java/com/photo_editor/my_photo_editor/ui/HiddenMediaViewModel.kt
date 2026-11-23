package com.photo_editor.my_photo_editor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Locked Folder screen state. Unlock state survives rotation but not leaving the screen. */
class HiddenMediaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _items = MutableStateFlow<List<MediaFile>>(emptyList())
    val items: StateFlow<List<MediaFile>> = _items.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    var isAuthInProgress = false

    fun unlock() {
        _isUnlocked.value = true
        load()
    }

    fun load() {
        if (!_isUnlocked.value) return
        viewModelScope.launch {
            _items.value = repository.loadHiddenMedia()
        }
    }
}
