package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.model.TrashedMedia
import com.photo_editor.my_photo_editor.repository.MediaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Recycle Bin screen state: trashed items, selection, restore / delete-forever requests. */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _items = MutableStateFlow<List<TrashedMedia>>(emptyList())
    val items: StateFlow<List<TrashedMedia>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selection = MutableStateFlow<Set<Uri>>(emptySet())
    val selection: StateFlow<Set<Uri>> = _selection.asStateFlow()

    private val _events = MutableSharedFlow<TrashEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TrashEvent> = _events.asSharedFlow()

    val isSupported: Boolean get() = repository.isRecycleBinSupported

    fun load() {
        if (!isSupported) return
        _isLoading.value = true
        viewModelScope.launch {
            val list = repository.loadTrashedMedia()
            _items.value = list
            val valid = list.map { it.media.uri }.toSet()
            _selection.value = _selection.value.intersect(valid)
            _isLoading.value = false
        }
    }

    fun toggle(uri: Uri) {
        val current = _selection.value
        _selection.value = if (current.contains(uri)) current - uri else current + uri
    }

    fun toggleSelectAll() {
        val all = _items.value.map { it.media.uri }.toSet()
        _selection.value = if (_selection.value.containsAll(all)) emptySet() else all
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun restoreSelected() = request(Action.RESTORE, _selection.value.toList())
    fun deleteSelected() = request(Action.DELETE, _selection.value.toList())
    fun emptyBin() = request(Action.DELETE, _items.value.map { it.media.uri })

    private fun request(action: Action, uris: List<Uri>) {
        if (uris.isEmpty()) return
        try {
            val sender = when (action) {
                Action.RESTORE -> repository.createRestoreRequest(uris)
                Action.DELETE -> repository.createDeleteRequest(uris)
            } ?: return
            _events.tryEmit(TrashEvent.LaunchIntentSender(sender, action, uris.size))
        } catch (e: Exception) {
            e.printStackTrace()
            _events.tryEmit(TrashEvent.Message("Action failed. Please try again."))
        }
    }

    fun onRequestResult(action: Action, count: Int, success: Boolean) {
        if (!success) return
        _events.tryEmit(
            TrashEvent.Message(
                when (action) {
                    Action.RESTORE -> "Restored $count item(s)"
                    Action.DELETE -> "Permanently deleted $count item(s)"
                }
            )
        )
        clearSelection()
        load()
    }

    enum class Action { RESTORE, DELETE }

    sealed class TrashEvent {
        data class Message(val text: String) : TrashEvent()
        data class LaunchIntentSender(val sender: IntentSender, val action: Action, val count: Int) : TrashEvent()
    }
}
