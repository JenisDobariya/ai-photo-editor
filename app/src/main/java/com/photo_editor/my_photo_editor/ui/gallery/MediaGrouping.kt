package com.photo_editor.my_photo_editor.ui.gallery

import android.net.Uri
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.Memory
import com.photo_editor.my_photo_editor.model.SortOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Turns a flat media list into grid rows with date section headers. */
object MediaGrouping {

    fun build(
        media: List<MediaFile>,
        groupBy: GroupBy,
        sortOrder: SortOrder,
        selection: Set<Uri>,
        memories: List<Memory> = emptyList()
    ): List<GridItem> {
        val items = ArrayList<GridItem>(media.size + 64)
        val selectionMode = selection.isNotEmpty()
        // Keep the memories row in selection mode too, so the grid does not jump under the finger on long-press.
        if (memories.isNotEmpty()) items.add(GridItem.Memories(memories))

        val dateSorted = sortOrder == SortOrder.DATE_DESC || sortOrder == SortOrder.DATE_ASC
        if (!dateSorted) {
            val label = if (sortOrder == SortOrder.NAME_ASC) "Sorted by name" else "Largest first"
            addSection(items, "all", label, media, selection, selectionMode)
            return items
        }

        val keyCal = Calendar.getInstance()
        var currentKey = Long.MIN_VALUE
        var currentGroup = ArrayList<MediaFile>()
        var currentTitle = ""
        media.forEach { file ->
            val key = groupKey(keyCal, file.dateAdded, groupBy)
            if (key != currentKey) {
                if (currentGroup.isNotEmpty()) {
                    addSection(items, currentKey.toString(), currentTitle, currentGroup, selection, selectionMode)
                }
                currentKey = key
                currentTitle = groupTitle(file.dateAdded, groupBy)
                currentGroup = ArrayList()
            }
            currentGroup.add(file)
        }
        if (currentGroup.isNotEmpty()) {
            addSection(items, currentKey.toString(), currentTitle, currentGroup, selection, selectionMode)
        }
        return items
    }

    private fun addSection(
        items: MutableList<GridItem>,
        id: String,
        title: String,
        files: List<MediaFile>,
        selection: Set<Uri>,
        selectionMode: Boolean
    ) {
        val uris = files.map { it.uri }
        items.add(
            GridItem.Header(
                id = id,
                title = title,
                subtitle = if (files.size == 1) "1 item" else "${files.size} items",
                uris = uris,
                allSelected = selectionMode && uris.all { selection.contains(it) },
                selectionMode = selectionMode
            )
        )
        files.forEach { items.add(GridItem.Media(it, selection.contains(it.uri), selectionMode)) }
    }

    private fun groupKey(cal: Calendar, seconds: Long, groupBy: GroupBy): Long {
        cal.timeInMillis = seconds * 1000L
        val year = cal.get(Calendar.YEAR).toLong()
        return when (groupBy) {
            GroupBy.DAY -> year * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            GroupBy.MONTH -> year * 100 + cal.get(Calendar.MONTH)
            GroupBy.YEAR -> year
        }
    }

    fun groupTitle(seconds: Long, groupBy: GroupBy): String {
        val date = Date(seconds * 1000L)
        val cal = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        val sameYear = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        return when (groupBy) {
            GroupBy.YEAR -> cal.get(Calendar.YEAR).toString()
            GroupBy.MONTH -> SimpleDateFormat(if (sameYear) "MMMM" else "MMMM yyyy", Locale.getDefault()).format(date)
            GroupBy.DAY -> {
                val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                when {
                    isSameDay(cal, now) -> "Today"
                    isSameDay(cal, yesterday) -> "Yesterday"
                    sameYear -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(date)
                    else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date)
                }
            }
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
