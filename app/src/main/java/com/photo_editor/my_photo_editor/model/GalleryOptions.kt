package com.photo_editor.my_photo_editor.model

import android.net.Uri

/** Quick filters shown as chips at the top of the Photos tab. */
enum class MediaFilter {
    ALL, PHOTOS, VIDEOS, FAVORITES, SCREENSHOTS
}

/** Sort orders for the photo grid. */
enum class SortOrder {
    DATE_DESC, DATE_ASC, NAME_ASC, SIZE_DESC
}

/** How the photo grid is split into sections. */
enum class GroupBy {
    DAY, MONTH, YEAR
}

/** Sort orders for the albums tab. */
enum class AlbumSort {
    RECENT, NAME, COUNT
}

/** "On this day" style memory built from photos taken in previous years. */
data class Memory(
    val title: String,
    val subtitle: String,
    val cover: MediaFile,
    val uris: List<Uri>
)

/** Aggregated numbers for the profile / library overview. */
data class LibraryStats(
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val albumCount: Int = 0,
    val favoriteCount: Int = 0,
    val totalBytes: Long = 0L
)

/** Item inside the system Recycle Bin (Android 11+). */
data class TrashedMedia(
    val media: MediaFile,
    val expiresAtSeconds: Long,
    val dataPath: String?
)

/** Detailed information shown in the info bottom sheet. */
data class MediaDetails(
    val name: String,
    val dateText: String?,
    val timeText: String?,
    val resolution: String?,
    val megapixels: String?,
    val sizeText: String?,
    val mimeType: String?,
    val path: String?,
    val durationText: String?,
    val camera: String?,
    val exposure: String?,
    val latitude: Double?,
    val longitude: Double?
)

/** A "Things" album built from on-device image labels (e.g. Dogs, Food, Beach). */
data class ThingCollection(
    val label: String,
    val title: String,
    val cover: Uri,
    val uris: List<Uri>
)
