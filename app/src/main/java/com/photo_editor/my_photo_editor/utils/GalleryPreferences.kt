package com.photo_editor.my_photo_editor.utils

import android.content.Context
import com.photo_editor.my_photo_editor.model.AlbumSort
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.SortOrder

/** Small SharedPreferences wrapper for gallery view options. */
class GalleryPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)

    var gridColumns: Int
        get() = prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)).apply()

    var sortOrder: SortOrder
        get() = enumOr(prefs.getString(KEY_SORT, null), SortOrder.DATE_DESC)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var groupBy: GroupBy
        get() = enumOr(prefs.getString(KEY_GROUP, null), GroupBy.DAY)
        set(value) = prefs.edit().putString(KEY_GROUP, value.name).apply()

    var albumSort: AlbumSort
        get() = enumOr(prefs.getString(KEY_ALBUM_SORT, null), AlbumSort.RECENT)
        set(value) = prefs.edit().putString(KEY_ALBUM_SORT, value.name).apply()

    var showMemories: Boolean
        get() = prefs.getBoolean(KEY_MEMORIES, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORIES, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        runCatching { if (name == null) default else enumValueOf<T>(name) }.getOrDefault(default)

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 7
        const val DEFAULT_COLUMNS = 4
        private const val KEY_COLUMNS = "grid_columns"
        private const val KEY_SORT = "sort_order"
        private const val KEY_GROUP = "group_by"
        private const val KEY_ALBUM_SORT = "album_sort"
        private const val KEY_MEMORIES = "show_memories"
    }
}
