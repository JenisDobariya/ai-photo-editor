package com.photo_editor.my_photo_editor.ui.cleaner

import android.app.Application
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.repository.MediaRepository
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Storage cleaner: finds duplicates, similar shots, blurry photos, old screenshots and large
 * videos, keeps a per-category selection and moves the chosen items to the Recycle Bin.
 *
 * Selection model: each category has a default (groups: everything except the best photo;
 * flat lists: nothing) plus the user's explicit choices ([overrides]), so selections survive
 * re-analysis while the background index is still growing.
 */
class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repository = CleanerRepository(application)
    private val mediaRepository = MediaRepository(application)

    private val library = MutableStateFlow<List<CleanerMedia>?>(null)
    private val analysis = MutableStateFlow<CleanerAnalysis?>(null)
    private val category = MutableStateFlow(CleanerCategory.DUPLICATES)
    private val overrides = MutableStateFlow<Map<CleanerCategory, Map<Uri, Boolean>>>(emptyMap())

    private val _state = MutableStateFlow(CleanerUiState())
    val state: StateFlow<CleanerUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CleanerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CleanerEvent> = _events.asSharedFlow()

    /** Background photo analysis progress (for the "Analysing photos…" bar). */
    val analysisProgress: StateFlow<SmartIndexRepository.Progress> = SmartIndexRepository.progress

    val isRecycleBinSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private var started = false
    private var autoPickCategory = true
    private var reloadJob: Job? = null
    private var pendingDelete: PendingDelete? = null

    private class PendingDelete(val uris: List<Uri>, val bytes: Long)

    init {
        // Re-analyse whenever the library or the index changes. conflate(): while one pass
        // runs only the newest input is kept, so a fast-growing index never queues up work.
        viewModelScope.launch {
            combine(library.filterNotNull(), SmartIndexRepository.observe(app)) { lib, index -> lib to index }
                .conflate()
                .collect { (lib, index) ->
                    val result = withContext(Dispatchers.Default) { CleanerAnalyzer.analyze(lib, index) }
                    // Open on the first category with results, once the index has data
                    // (an empty index would wrongly skip duplicates / similar).
                    if (autoPickCategory && index.isNotEmpty()) {
                        autoPickCategory = false
                        if (result.result(category.value).isEmpty) {
                            CleanerCategory.entries.firstOrNull { !result.result(it).isEmpty }?.let { category.value = it }
                        }
                    }
                    analysis.value = result
                    // Throttle while the index is still growing.
                    if (SmartIndexRepository.progress.value.running) delay(REANALYSE_THROTTLE_MS)
                }
        }
        viewModelScope.launch {
            combine(analysis.filterNotNull(), category, overrides) { a, c, o -> Triple(a, c, o) }
                .conflate()
                .collect { (a, c, o) ->
                    _state.value = withContext(Dispatchers.Default) { buildState(a, c, o) }
                }
        }
    }

    /** Call once from onCreate (not after rotation). */
    fun start(startCategoryKey: String?) {
        if (started) return
        started = true
        CleanerCategory.fromKey(startCategoryKey)?.let {
            category.value = it
            autoPickCategory = false
        }
        reload()
    }

    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val lib = repository.loadLibrary()
            library.value = lib
            repository.syncIndex(lib)
        }
    }

    fun selectCategory(c: CleanerCategory) {
        autoPickCategory = false
        if (category.value == c) return
        AnalyticsManager.logEvent("Cleaner_category_${c.key}")
        category.value = c
    }

    fun setSelected(uri: Uri, selected: Boolean) {
        val c = category.value
        updateOverrides(c) { it[uri] = selected }
    }

    /** Group header action: clear the group when anything in it is selected, else select all but the best. */
    fun toggleGroup(groupId: String) {
        val c = category.value
        val group = analysis.value?.result(c)?.groups?.firstOrNull { it.id == groupId } ?: return
        val current = overrides.value[c].orEmpty()
        val anySelected = group.items.any { current[it.uri] ?: defaultSelected(c, it, group) }
        updateOverrides(c) { map ->
            group.items.forEach { item -> map[item.uri] = !anySelected && item.uri != group.best.uri }
        }
    }

    /** Header "Select all / Deselect all" for the current category. */
    fun toggleSelectAll() {
        val c = category.value
        val result = analysis.value?.result(c) ?: return
        if (_state.value.selectedCount > 0) {
            updateOverrides(c) { map -> result.allItems.forEach { map[it.uri] = false } }
        } else if (c.grouped) {
            // Back to the suggestion: everything except the best photo of each group.
            overrides.value = overrides.value - c
        } else {
            updateOverrides(c) { map -> result.allItems.forEach { map[it.uri] = true } }
        }
    }

    // ------------------------------------------------------------------ delete

    fun deleteSelected() {
        val s = _state.value
        val uris = s.selectedUris
        if (uris.isEmpty()) return
        AnalyticsManager.logEvent("Cleaner_delete_click")
        val pending = PendingDelete(uris, s.selectedBytes)
        if (isRecycleBinSupported) {
            try {
                val sender = mediaRepository.createTrashRequest(uris)
                if (sender == null) {
                    _events.tryEmit(CleanerEvent.Message("Couldn't move items to Recycle Bin"))
                    return
                }
                pendingDelete = pending
                _events.tryEmit(CleanerEvent.LaunchTrashRequest(sender))
            } catch (e: Exception) {
                e.printStackTrace()
                _events.tryEmit(
                    CleanerEvent.Message(
                        if (uris.size > 500) "Too many items at once. Try deleting fewer items."
                        else "Couldn't move items to Recycle Bin"
                    )
                )
            }
        } else {
            pendingDelete = pending
            _events.tryEmit(CleanerEvent.ConfirmPermanentDelete(uris.size, pending.bytes))
        }
    }

    /** Result of the Android 11+ system trash confirmation. */
    fun onTrashResult(success: Boolean) {
        val pending = pendingDelete ?: return
        pendingDelete = null
        if (!success) return
        AnalyticsManager.logEvent("Cleaner_trash_success")
        onDeleted(pending.uris, pending.uris.size, pending.bytes, recycleBin = true)
    }

    fun confirmPermanentDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            val deleted = mediaRepository.deleteLegacy(pending.uris)
            AnalyticsManager.logEvent("Cleaner_delete_success")
            onDeleted(pending.uris, deleted, if (deleted == pending.uris.size) pending.bytes else -1L, recycleBin = false)
        }
    }

    fun cancelPendingDelete() {
        pendingDelete = null
    }

    private fun onDeleted(uris: List<Uri>, count: Int, bytes: Long, recycleBin: Boolean) {
        val removed = uris.toHashSet()
        // Update the screen right away; the reload below confirms with MediaStore.
        library.value = library.value?.filterNot { it.uri in removed }
        overrides.value = overrides.value.mapValues { (_, map) -> map.filterKeys { it !in removed } }
        val itemsText = if (count == 1) "1 item" else "$count items"
        val text = when {
            count <= 0 -> "Nothing was deleted"
            bytes > 0 && recycleBin -> "Freed ${formatSize(bytes)} · $itemsText moved to Recycle Bin"
            bytes > 0 -> "Freed ${formatSize(bytes)} · deleted $itemsText"
            recycleBin -> "Moved $itemsText to Recycle Bin"
            else -> "Deleted $itemsText"
        }
        _events.tryEmit(CleanerEvent.Message(text))
        reload()
    }

    // ------------------------------------------------------------------ state

    private inline fun updateOverrides(c: CleanerCategory, block: (MutableMap<Uri, Boolean>) -> Unit) {
        val all = overrides.value
        val map = HashMap(all[c].orEmpty())
        block(map)
        overrides.value = all + (c to map)
    }

    private fun defaultSelected(c: CleanerCategory, item: CleanerMedia, group: CleanerGroup?): Boolean =
        c.grouped && group != null && item.uri != group.best.uri

    private fun buildState(
        a: CleanerAnalysis,
        c: CleanerCategory,
        allOverrides: Map<CleanerCategory, Map<Uri, Boolean>>
    ): CleanerUiState {
        val result = a.result(c)
        val o = allOverrides[c].orEmpty()
        val rows = ArrayList<CleanerRow>()
        val selected = ArrayList<Uri>()
        val uris = ArrayList<Uri>()
        var selectedBytes = 0L

        if (c.grouped) {
            val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (group in result.groups) {
                var groupSelBytes = 0L
                var groupSelCount = 0
                val mediaRows = ArrayList<CleanerRow.Media>(group.items.size)
                for (item in group.items) {
                    val isSel = o[item.uri] ?: defaultSelected(c, item, group)
                    if (isSel) {
                        groupSelBytes += item.size
                        groupSelCount++
                        selected.add(item.uri)
                        selectedBytes += item.size
                    }
                    uris.add(item.uri)
                    mediaRows.add(
                        CleanerRow.Media(
                            media = item,
                            selected = isSel,
                            isBest = item.uri == group.best.uri,
                            badge = formatSize(item.size),
                            groupId = group.id
                        )
                    )
                }
                val noun = if (c == CleanerCategory.DUPLICATES) "copies" else "similar photos"
                val date = dateFormat.format(Date(group.best.timeMs))
                rows.add(
                    CleanerRow.GroupHeader(
                        groupId = group.id,
                        title = "${group.items.size} $noun",
                        subtitle = if (groupSelCount > 0) "$date · frees ${formatSize(groupSelBytes)}" else "$date · keeping all",
                        actionText = if (groupSelCount > 0) "Keep all" else "Select extras"
                    )
                )
                rows.addAll(mediaRows)
            }
        } else {
            for (item in result.items) {
                val isSel = o[item.uri] ?: false
                if (isSel) {
                    selected.add(item.uri)
                    selectedBytes += item.size
                }
                uris.add(item.uri)
                val badge = if (item.isVideo && item.durationMs > 0) {
                    "${formatSize(item.size)} · ${MediaRepository.formatDuration(item.durationMs)}"
                } else formatSize(item.size)
                rows.add(CleanerRow.Media(media = item, selected = isSel, isBest = false, badge = badge, groupId = null))
            }
        }

        val counts = CleanerCategory.entries.associateWith { a.result(it).reclaimableCount }
        return CleanerUiState(
            loading = false,
            category = c,
            rows = rows,
            counts = counts,
            totalReclaimableBytes = a.totalReclaimableBytes,
            categoryInfo = categoryInfo(result),
            categoryEmpty = result.isEmpty,
            selectedUris = selected,
            selectedBytes = selectedBytes,
            categoryUris = uris
        )
    }

    private fun categoryInfo(r: CategoryResult): String {
        if (r.isEmpty) return ""
        val size = formatSize(r.reclaimableBytes)
        return when (r.category) {
            CleanerCategory.DUPLICATES ->
                "${plural(r.groups.size, "group", "groups")} · ${plural(r.reclaimableCount, "extra copy", "extra copies")} · $size"
            CleanerCategory.SIMILAR ->
                "${plural(r.groups.size, "group", "groups")} · ${plural(r.reclaimableCount, "extra photo", "extra photos")} · $size"
            CleanerCategory.BLURRY -> "${plural(r.items.size, "blurry photo", "blurry photos")} · $size"
            CleanerCategory.SCREENSHOTS -> "${plural(r.items.size, "screenshot", "screenshots")} · $size"
            CleanerCategory.LARGE_VIDEOS -> "${plural(r.items.size, "video", "videos")} over 50 MB · $size"
        }
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(app, bytes)

    private companion object {
        const val REANALYSE_THROTTLE_MS = 1500L
    }
}
