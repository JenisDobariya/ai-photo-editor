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
    var isFavorite: Boolean = false,
    // Extended metadata (optional, filled when available)
    val size: Long = 0L,          // bytes
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String? = null,
    val dateTaken: Long = 0L,     // milliseconds, 0 when unknown
    val relativePath: String? = null
) {
    /** True when the file lives in a screenshots / screen recordings folder. */
    val isScreenshot: Boolean
        get() = album.contains("screenshot", ignoreCase = true) ||
                (relativePath?.contains("screenshot", ignoreCase = true) == true)

    /** Best known capture time in seconds (falls back to date added). */
    val displayDateSeconds: Long
        get() = if (dateTaken > 0L) dateTaken / 1000L else dateAdded
}
