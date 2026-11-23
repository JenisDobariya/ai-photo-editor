package com.photo_editor.my_photo_editor.ui.pdf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import com.photo_editor.my_photo_editor.utils.PdfMaker
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Photos → PDF: page list (ordered), page options and the background build. */
class PhotosToPdfViewModel(application: Application) : AndroidViewModel(application) {

    /** [id] is unique per added page, so the same photo can appear twice. */
    data class Page(val id: Long, val uri: Uri)

    data class UiState(
        val pages: List<Page> = emptyList(),
        val options: PdfMaker.Options = PdfMaker.Options(),
        val fileName: String = defaultFileName()
    )

    data class Progress(val done: Int, val total: Int)

    /** Finished PDF. [viewUri] is the saved copy, [shareUri] a FileProvider uri of the same file. */
    data class Done(val viewUri: Uri, val shareUri: Uri?, val pages: Int, val failed: Int)

    sealed class Event {
        data class Message(val text: String) : Event()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Kept until the activity shows and dismisses it, so it survives rotation / backgrounding. */
    private val _done = MutableStateFlow<Done?>(null)
    val done: StateFlow<Done?> = _done.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var nextId = 0L
    private var initialized = false
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** First uris from the launching intent; ignored after the first call (e.g. after rotation). */
    fun init(uris: List<Uri>) {
        if (initialized) return
        initialized = true
        addPhotos(uris)
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        initialized = true
        val added = uris.map { Page(nextId++, it) }
        _state.update { it.copy(pages = it.pages + added) }
    }

    fun remove(id: Long) {
        _state.update { s -> s.copy(pages = s.pages.filterNot { it.id == id }) }
    }

    /** Applies the order the user dragged the pages into. */
    fun reorder(ids: List<Long>) {
        val byId = _state.value.pages.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        if (ordered.size != byId.size) return
        _state.update { it.copy(pages = ordered) }
    }

    fun setPageSize(value: PdfMaker.PageSize) = _state.update { it.copy(options = it.options.copy(pageSize = value)) }
    fun setOrientation(value: PdfMaker.Orientation) = _state.update { it.copy(options = it.options.copy(orientation = value)) }
    fun setMargin(value: PdfMaker.Margin) = _state.update { it.copy(options = it.options.copy(margin = value)) }
    fun setQuality(value: PdfMaker.Quality) = _state.update { it.copy(options = it.options.copy(quality = value)) }

    fun setFileName(name: String) {
        val clean = sanitize(name)
        _state.update { it.copy(fileName = clean.ifEmpty { defaultFileName() }) }
    }

    fun consumeDone() { _done.value = null }

    fun cancel() {
        if (job?.isActive == true) {
            job?.cancel()
            _progress.value = null
            _events.trySend(Event.Message("PDF cancelled"))
        }
    }

    fun create() {
        if (isBusy) return
        val snapshot = _state.value
        if (snapshot.pages.isEmpty()) {
            _events.trySend(Event.Message("Add at least one photo"))
            return
        }
        AnalyticsManager.logEvent("PhotosToPdf_create")
        val app = getApplication<Application>()
        val name = snapshot.fileName.ifEmpty { defaultFileName() }
        val uris = snapshot.pages.map { it.uri }
        _progress.value = Progress(0, uris.size)
        job = viewModelScope.launch {
            var cacheFile: File? = null
            try {
                val outcome = withContext<Pair<Uri?, PdfMaker.Result>>(Dispatchers.IO) {
                    val file = File(ShareHelper.freshShareDir(app), "$name.pdf")
                    cacheFile = file
                    val result = PdfMaker.create(app, uris, snapshot.options, file) { done, total ->
                        if (job?.isCancelled != true) _progress.value = Progress(done, total)
                    }
                    if (result.pages == 0) {
                        file.delete()
                        return@withContext null to result
                    }
                    val saved = MediaStoreSaver.saveFile(
                        app, file, "$name.pdf", "application/pdf",
                        MediaStoreSaver.Kind.DOCUMENT, MediaStoreSaver.DOCUMENTS_DIR
                    )
                    saved to result
                }
                val (savedUri, result) = outcome
                when {
                    result.pages == 0 ->
                        _events.trySend(Event.Message("None of the photos could be read"))
                    savedUri == null ->
                        _events.trySend(Event.Message("Couldn't save the PDF"))
                    else -> {
                        val file = cacheFile
                        val shareUri = try {
                            if (file != null && file.exists()) ShareHelper.fileUri(app, file) else null
                        } catch (e: Exception) {
                            null
                        }
                        // On Android 9 and below the saver can fall back to a file:// uri,
                        // which other apps can't open, so prefer the FileProvider copy then.
                        val viewUri = if (savedUri.scheme == "file" && shareUri != null) shareUri else savedUri
                        AnalyticsManager.logEvent("PhotosToPdf_saved")
                        _done.value = Done(viewUri, shareUri ?: viewUri, result.pages, result.failed)
                    }
                }
            } catch (e: CancellationException) {
                cacheFile?.delete()
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                cacheFile?.delete()
                _events.trySend(Event.Message("Couldn't create the PDF"))
            } finally {
                _progress.value = null
            }
        }
    }

    companion object {
        fun defaultFileName(): String =
            "Photos_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

        /** Keeps the name safe for every file system and strips a typed ".pdf". */
        fun sanitize(name: String): String {
            var n = name.trim()
            if (n.endsWith(".pdf", ignoreCase = true)) n = n.dropLast(4)
            return n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100)
        }
    }
}
