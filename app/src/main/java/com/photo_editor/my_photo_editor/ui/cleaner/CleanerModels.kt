package com.photo_editor.my_photo_editor.ui.cleaner

import android.content.IntentSender
import android.net.Uri

/** The cleaner tabs. [key] is the public value used by [com.photo_editor.my_photo_editor.CleanerActivity.intent]. */
enum class CleanerCategory(
    val key: String,
    val title: String,
    /** Duplicate / similar photos are shown as groups with one "best" photo kept. */
    val grouped: Boolean,
    /** Needs the background photo analysis (hashes / sharpness) to produce results. */
    val needsIndex: Boolean
) {
    DUPLICATES("duplicates", "Duplicates", grouped = true, needsIndex = true),
    SIMILAR("similar", "Similar", grouped = true, needsIndex = true),
    BLURRY("blurry", "Blurry", grouped = false, needsIndex = true),
    SCREENSHOTS("screenshots", "Screenshots", grouped = false, needsIndex = false),
    LARGE_VIDEOS("large_videos", "Large videos", grouped = false, needsIndex = false);

    companion object {
        fun fromKey(key: String?): CleanerCategory? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** One photo or video of the library (Locked Folder items already removed). */
data class CleanerMedia(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val dateTakenMs: Long,
    val album: String,
    val relativePath: String?,
    val mimeType: String?,
    val durationMs: Long
) {
    /** Capture time in ms: date taken when known, otherwise date added. */
    val timeMs: Long get() = if (dateTakenMs > 0L) dateTakenMs else dateAddedSec * 1000L

    val isScreenshot: Boolean
        get() = album.contains("screenshot", ignoreCase = true) ||
                (relativePath?.contains("screenshot", ignoreCase = true) == true)
}

/** Duplicate or similar photos. [best] is the one we suggest keeping; it is also in [items]. */
data class CleanerGroup(
    val id: String,
    val items: List<CleanerMedia>,
    val best: CleanerMedia
) {
    /** Bytes freed when every photo except [best] is deleted. */
    val extraBytes: Long get() = items.sumOf { if (it.uri == best.uri) 0L else it.size }
}

data class CategoryResult(
    val category: CleanerCategory,
    /** Filled for grouped categories. */
    val groups: List<CleanerGroup> = emptyList(),
    /** Filled for flat categories. */
    val items: List<CleanerMedia> = emptyList(),
    /** Suggested space to free: extras of every group, or every item of a flat list. */
    val reclaimableBytes: Long = 0L,
    /** Items that can be removed (extras for groups, all items for flat lists). */
    val reclaimableCount: Int = 0
) {
    val isEmpty: Boolean get() = groups.isEmpty() && items.isEmpty()

    /** Every item shown in this category, in display order. */
    val allItems: List<CleanerMedia>
        get() = if (category.grouped) groups.flatMap { it.items } else items
}

data class CleanerAnalysis(
    val results: Map<CleanerCategory, CategoryResult>,
    /** Suggested space to free across all categories (an item counted once even if in several). */
    val totalReclaimableBytes: Long
) {
    fun result(category: CleanerCategory): CategoryResult = results[category] ?: CategoryResult(category)
}

/** Rows of the cleaner list. */
sealed class CleanerRow {
    abstract val key: String

    data class GroupHeader(
        val groupId: String,
        val title: String,
        val subtitle: String,
        val actionText: String
    ) : CleanerRow() {
        override val key: String get() = "h:$groupId"
    }

    data class Media(
        val media: CleanerMedia,
        val selected: Boolean,
        val isBest: Boolean,
        val badge: String?,
        val groupId: String?
    ) : CleanerRow() {
        override val key: String get() = "m:${groupId.orEmpty()}:${media.uri}"
    }
}

data class CleanerUiState(
    /** True until the library has been read and analysed once. */
    val loading: Boolean = true,
    val category: CleanerCategory = CleanerCategory.DUPLICATES,
    val rows: List<CleanerRow> = emptyList(),
    /** Number shown on each chip (removable items). */
    val counts: Map<CleanerCategory, Int> = emptyMap(),
    val totalReclaimableBytes: Long = 0L,
    /** e.g. "5 groups · 12 extra copies · 34 MB". */
    val categoryInfo: String = "",
    val categoryEmpty: Boolean = true,
    val selectedUris: List<Uri> = emptyList(),
    val selectedBytes: Long = 0L,
    /** Every uri shown in the current category, in order (for the viewer). */
    val categoryUris: List<Uri> = emptyList()
) {
    val selectedCount: Int get() = selectedUris.size
}

sealed class CleanerEvent {
    data class Message(val text: String) : CleanerEvent()
    /** Android 11+: system "move to Recycle Bin" confirmation. */
    data class LaunchTrashRequest(val sender: IntentSender) : CleanerEvent()
    /** Android 10 and older: ask before a permanent delete. */
    data class ConfirmPermanentDelete(val count: Int, val bytes: Long) : CleanerEvent()
}
