package com.photo_editor.my_photo_editor.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * On-device analysis of one photo, computed once in the background by
 * [com.photo_editor.my_photo_editor.repository.SmartIndexRepository] and reused by
 * Smart Search, the Things albums and the storage cleaner.
 *
 * [dateAdded] + [size] identify the file version: when either changes the photo is analysed again.
 */
@Entity(tableName = "media_index")
data class MediaIndex(
    @PrimaryKey
    val uri: String,
    val dateAdded: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    /** 64-bit difference hash of the picture; near-identical photos have hashes a few bits apart. */
    val dHash: Long,
    /** Variance of the Laplacian on a small grey copy; low values mean a blurry photo. */
    val sharpness: Float,
    /**
     * Lower-case labels found by on-device image labeling, separated by '|', strongest first
     * (e.g. "dog|grass|pet"). Null when labeling could not run yet (model still downloading),
     * so the photo is labelled on a later pass.
     */
    val labels: String?,
    val indexedAt: Long = System.currentTimeMillis()
)

/** Just the identity columns, to find which photos are new or changed without loading everything. */
data class MediaIndexKey(
    val uri: String,
    val dateAdded: Long,
    val size: Long,
    val labels: String?
)
