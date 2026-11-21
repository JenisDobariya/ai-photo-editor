package com.photo_editor.my_photo_editor.model

import android.net.Uri

enum class MediaType {
    IMAGE, VIDEO
}

data class MediaFile(
    val uri: Uri,
    val name: String,
    val album: String,
    val dateAdded: Long,
    val type: MediaType = MediaType.IMAGE,
    val duration: Long = 0L, // in milliseconds
    var isFavorite: Boolean = false
)
