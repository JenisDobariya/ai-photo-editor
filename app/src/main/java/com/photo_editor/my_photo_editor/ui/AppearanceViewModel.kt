package com.photo_editor.my_photo_editor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.photo_editor.my_photo_editor.utils.ThemePreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Profile → Appearance: current theme mode and accent, and changing them. */
class AppearanceViewModel(application: Application) : AndroidViewModel(application) {

    data class State(val mode: ThemePreferences.Mode, val accent: ThemePreferences.Accent)

    sealed interface Event {
        /** The accent changed: the screen must be recreated to show it. */
        data object AccentChanged : Event
    }

    private val _state = MutableStateFlow(
        State(ThemePreferences.mode(application), ThemePreferences.accent(application))
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Light / Dark / System. AppCompat recreates the open screens itself. */
    fun setMode(mode: ThemePreferences.Mode) {
        if (mode == _state.value.mode) return
        _state.value = _state.value.copy(mode = mode)
        ThemePreferences.setMode(getApplication(), mode)
    }

    fun setAccent(accent: ThemePreferences.Accent) {
        if (accent == _state.value.accent) return
        _state.value = _state.value.copy(accent = accent)
        ThemePreferences.setAccent(getApplication(), accent)
        _events.tryEmit(Event.AccentChanged)
    }
}
