package com.photo_editor.my_photo_editor.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Media moved to the app's Locked Folder (hidden from every gallery view). */
@Entity(tableName = "hidden_media")
data class HiddenMedia(
    @PrimaryKey
    val uri: String,
    val hiddenDate: Long = System.currentTimeMillis()
)
