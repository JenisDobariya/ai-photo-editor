package com.photo_editor.my_photo_editor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Studio tab logic: the Clean up hero, the Smart tools grid and saving document scans. */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    enum class Tool { CLEAN_UP, SCAN, PDF, COMPRESS, ENHANCE, BLUR_FACES, COPY_TEXT, TRIM, GIF }

    /** One Smart tool in the 4-column grid. [badge] is a small corner label (null = none). */
    data class ToolItem(val tool: Tool, val title: String, val icon: Int, val tint: Int, val badge: String? = null)

    /** What the Clean up card says about the on-device photo analysis. */
    data class CleanupStatus(val text: String, val running: Boolean, val percent: Int)

    sealed class Event {
        data class ScanSaved(val pdfUri: Uri, val pageUris: List<Uri>) : Event()
        data class PagesSaved(val count: Int) : Event()
        data class Message(val text: String) : Event()
    }

    /** Two full rows of four. Tints stay inside the app palette (blue, light blue, teal, pink). */
    val tools: List<ToolItem> = listOf(
        ToolItem(Tool.SCAN, "Scan document", R.drawable.ic_t_scan, R.color.lumina_gold, badge = "AI"),
        ToolItem(Tool.COPY_TEXT, "Copy text", R.drawable.ic_t_text, R.color.lumina_accent, badge = "AI"),
        ToolItem(Tool.ENHANCE, "Auto enhance", R.drawable.ic_magic_wand, R.color.lumina_teal),
        ToolItem(Tool.BLUR_FACES, "Blur faces", R.drawable.ic_t_face, R.color.lumina_favorite, badge = "AI"),
        ToolItem(Tool.PDF, "Photos to PDF", R.drawable.ic_t_pdf, R.color.lumina_accent),
        ToolItem(Tool.COMPRESS, "Compress", R.drawable.ic_t_compress, R.color.lumina_teal),
        ToolItem(Tool.TRIM, "Trim video", R.drawable.ic_t_trim, R.color.lumina_gold),
        ToolItem(Tool.GIF, "Video to GIF", R.drawable.ic_t_gif, R.color.lumina_favorite)
    )

    val cleanupStatus: StateFlow<CleanupStatus> = combine(
        SmartIndexRepository.progress,
        AppDatabase.getDatabase(application).mediaIndexDao().observeCount()
    ) { progress, count ->
        if (progress.running && progress.total > 0) {
            CleanupStatus(
                "Checking photos on your phone… ${String.format(Locale.getDefault(), "%,d", progress.done)} of " +
                    String.format(Locale.getDefault(), "%,d", progress.total),
                running = true,
                percent = (progress.fraction * 100).toInt().coerceIn(0, 100)
            )
        } else if (count > 0) {
            CleanupStatus(
                "${String.format(Locale.getDefault(), "%,d", count)} photos checked · duplicates, similar shots, blurry photos & big videos",
                running = false, percent = 100
            )
        } else {
            CleanupStatus(DEFAULT_CLEANUP_TEXT, running = false, percent = 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupStatus(DEFAULT_CLEANUP_TEXT, false, 0))

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Copies the scanner's PDF (temporary file) into Documents/PhotoEditor. */
    fun saveScan(pdfUri: Uri?, pageUris: List<Uri>) {
        if (pdfUri == null) {
            _events.tryEmit(Event.Message("Scan cancelled"))
            return
        }
        viewModelScope.launch {
            _saving.value = true
            val name = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
            val saved = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                MediaStoreSaver.save(getApplication(), name, "application/pdf", MediaStoreSaver.Kind.DOCUMENT) { out ->
                    resolver.openInputStream(pdfUri)?.use { it.copyTo(out) } != null
                }
            }
            _saving.value = false
            if (saved != null) _events.emit(Event.ScanSaved(saved, pageUris))
            else _events.emit(Event.Message("Couldn't save the scan"))
        }
    }

    /** Also keeps the scanned pages as photos (Pictures/PhotoEditor). */
    fun savePagesAsPhotos(pageUris: List<Uri>) {
        if (pageUris.isEmpty()) return
        viewModelScope.launch {
            _saving.value = true
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val count = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                pageUris.withIndex().count { (i, uri) ->
                    MediaStoreSaver.save(getApplication(), "Scan_${stamp}_${i + 1}.jpg", "image/jpeg", MediaStoreSaver.Kind.IMAGE) { out ->
                        resolver.openInputStream(uri)?.use { it.copyTo(out) } != null
                    } != null
                }
            }
            _saving.value = false
            _events.emit(Event.PagesSaved(count))
        }
    }

    private companion object {
        const val DEFAULT_CLEANUP_TEXT = "Finds duplicates, similar shots, blurry photos & big videos"
    }
}
