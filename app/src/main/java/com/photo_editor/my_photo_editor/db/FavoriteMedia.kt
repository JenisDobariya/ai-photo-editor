package com.photo_editor.my_photo_editor.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteMedia(
    @PrimaryKey
    val uri: String,
    val addedDate: Long = System.currentTimeMillis()
)
