package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.repository.MediaOrganizer
import com.photo_editor.my_photo_editor.repository.MediaRepository
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Multi-select state and batch actions (share, favorite, hide, move to Recycle Bin).
 * Shared by every grid screen so selection behaves the same everywhere.
 */
class MediaSelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _selection = MutableStateFlow<Set<Uri>>(emptySet())
    val selection: StateFlow<Set<Uri>> = _selection.asStateFlow()

    private val _events = MutableSharedFlow<SelectionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SelectionEvent> = _events.asSharedFlow()

    val isSelectionMode: Boolean get() = _selection.value.isNotEmpty()

    fun toggle(uri: Uri) {
        val current = _selection.value
        _selection.value = if (current.contains(uri)) current - uri else current + uri
    }

    fun setSelection(uris: Set<Uri>) { _selection.value = uris }

    fun select(uris: Collection<Uri>, selected: Boolean) {
        _selection.value = if (selected) _selection.value + uris else _selection.value - uris.toSet()
    }

    fun clear() { _selection.value = emptySet() }

    // ------------------------------------------------------------------ actions

    fun share(items: List<MediaFile>) {
        repository.buildShareIntent(items)?.let { _events.tryEmit(SelectionEvent.StartIntent(it)) }
    }

    /** Favorites all selected items, or un-favorites them when every item already is one. */
    fun toggleFavorite(items: List<MediaFile>) {
        if (items.isEmpty()) return
        val makeFavorite = !items.all { it.isFavorite }
        viewModelScope.launch {
            repository.setFavorite(items.map { it.uri }, makeFavorite)
            _events.emit(
                SelectionEvent.Message(
                    if (makeFavorite) "Added ${items.size} to Favorites" else "Removed ${items.size} from Favorites"
                )
            )
            clear()
        }
    }

    fun hide(items: List<MediaFile>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            repository.hide(items.map { it.uri })
            _events.emit(SelectionEvent.Message("Moved ${items.size} to Locked Folder"))
            clear()
        }
    }

    fun unhide(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            repository.unhide(uris)
            _events.emit(SelectionEvent.Message("Restored ${uris.size} to library"))
            _events.emit(SelectionEvent.Changed)
            clear()
        }
    }

    /** Android 11+: system Recycle Bin request. Older versions: permanent delete after confirmation. */
    fun moveToTrash(items: List<MediaFile>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        if (repository.isRecycleBinSupported) {
            try {
                repository.createTrashRequest(uris)?.let {
                    _events.tryEmit(SelectionEvent.LaunchIntentSender(it, PendingAction.TRASH))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _events.tryEmit(SelectionEvent.Message("Couldn't move items to Recycle Bin"))
            }
        } else {
            viewModelScope.launch {
                val deleted = repository.deleteLegacy(uris)
                _events.emit(SelectionEvent.Message("Deleted $deleted items"))
                clear()
            }
        }
    }

    fun onIntentSenderResult(action: PendingAction, success: Boolean) {
        if (!success) {
            pendingMove = null
            pendingRename = null
            return
        }
        when (action) {
            PendingAction.TRASH -> {
                val count = _selection.value.size
                _events.tryEmit(SelectionEvent.Message(if (count > 0) "Moved $count to Recycle Bin" else "Moved to Recycle Bin"))
                _events.tryEmit(SelectionEvent.Changed)
                clear()
            }
            PendingAction.MOVE -> {
                val move = pendingMove ?: return
                pendingMove = null
                viewModelScope.launch {
                    val moved = organizer.moveToAlbum(move.first, move.second)
                    val failed = move.first.size - moved
                    _events.emit(
                        SelectionEvent.Message(
                            if (failed == 0) "Moved $moved to ${move.second.name}"
                            else "Moved $moved to ${move.second.name} · $failed couldn't be moved"
                        )
                    )
                    _events.emit(SelectionEvent.Changed)
                    clear()
                }
            }
            PendingAction.RENAME -> {
                val rename = pendingRename ?: return
                pendingRename = null
                viewModelScope.launch {
                    val ok = organizer.rename(rename.first, rename.second)
                    _events.emit(SelectionEvent.Message(if (ok) "Renamed" else "Couldn't rename this file"))
                    if (ok) _events.emit(SelectionEvent.Changed)
                }
            }
        }
    }

    val isRecycleBinSupported: Boolean get() = repository.isRecycleBinSupported

    // ------------------------------------------------------------------ organise

    private val organizer = MediaOrganizer(application)
    private var pendingMove: Pair<List<Uri>, MediaOrganizer.AlbumTarget>? = null
    private var pendingRename: Pair<Uri, String>? = null

    /** Move and rename need Android 11's "allow editing" prompt; older versions can only copy. */
    val canEditInPlace: Boolean get() = organizer.canEditInPlace

    suspend fun albumTargets(): List<MediaOrganizer.AlbumTarget> = organizer.albumTargets()

    fun newAlbumTarget(name: String) = organizer.newAlbumTarget(name)

    fun copyToAlbum(items: List<MediaFile>, target: MediaOrganizer.AlbumTarget) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _events.emit(SelectionEvent.Message("Copying ${items.size} to ${target.name}…"))
            val copied = organizer.copyToAlbum(items, target)
            val failed = items.size - copied
            _events.emit(
                SelectionEvent.Message(
                    if (failed == 0) "Copied $copied to ${target.name}"
                    else "Copied $copied to ${target.name} · $failed couldn't be copied"
                )
            )
            _events.emit(SelectionEvent.Changed)
            clear()
        }
    }

    fun moveToAlbum(items: List<MediaFile>, target: MediaOrganizer.AlbumTarget) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        try {
            val sender = organizer.createWriteRequest(uris) ?: return
            pendingMove = uris to target
            _events.tryEmit(SelectionEvent.LaunchIntentSender(sender, PendingAction.MOVE))
        } catch (e: Exception) {
            e.printStackTrace()
            _events.tryEmit(SelectionEvent.Message("Couldn't move these items"))
        }
    }

    fun rename(uri: Uri, newName: String) {
        if (newName.isBlank()) return
        try {
            val sender = organizer.createWriteRequest(listOf(uri)) ?: return
            pendingRename = uri to newName
            _events.tryEmit(SelectionEvent.LaunchIntentSender(sender, PendingAction.RENAME))
        } catch (e: Exception) {
            e.printStackTrace()
            _events.tryEmit(SelectionEvent.Message("Couldn't rename this file"))
        }
    }

    /** Shares photos with their GPS location removed (privacy). */
    fun shareWithoutLocation(items: List<MediaFile>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val result = organizer.locationFreeCopies(items)
            if (result.uris.isEmpty()) {
                _events.emit(SelectionEvent.Message("Only photos can be shared without location"))
                return@launch
            }
            if (result.skipped > 0) {
                _events.emit(SelectionEvent.Message("${result.skipped} item(s) skipped (videos are not supported)"))
            }
            ShareHelper.shareIntent(result.uris, "image/jpeg", "Share without location")?.let {
                _events.emit(SelectionEvent.StartIntent(it))
            }
        }
    }

    enum class PendingAction { TRASH, MOVE, RENAME }

    sealed class SelectionEvent {
        data class Message(val text: String) : SelectionEvent()
        data class StartIntent(val intent: Intent) : SelectionEvent()
        data class LaunchIntentSender(val sender: IntentSender, val action: PendingAction) : SelectionEvent()
        /** Library content changed (e.g. after delete/unhide) and screens may want to reload. */
        data object Changed : SelectionEvent()
    }
}
