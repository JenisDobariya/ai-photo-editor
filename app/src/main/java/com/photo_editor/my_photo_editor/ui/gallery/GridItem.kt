package com.photo_editor.my_photo_editor.ui.gallery

import android.net.Uri
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.Memory

/** Rows rendered by [MediaGridAdapter]. */
sealed class GridItem {
    abstract val key: String

    data class Memories(val memories: List<Memory>) : GridItem() {
        override val key: String get() = "memories"
    }

    data class Header(
        val id: String,
        val title: String,
        val subtitle: String?,
        val uris: List<Uri>,
        val allSelected: Boolean,
        val selectionMode: Boolean
    ) : GridItem() {
        override val key: String get() = "h_$id"
    }

    data class Media(
        val file: MediaFile,
        val selected: Boolean,
        val selectionMode: Boolean
    ) : GridItem() {
        override val key: String get() = "m_${file.uri}"
    }
}
