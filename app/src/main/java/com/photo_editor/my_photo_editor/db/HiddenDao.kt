package com.photo_editor.my_photo_editor.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenDao {
    @Query("SELECT * FROM hidden_media ORDER BY hiddenDate DESC")
    fun getAllHidden(): Flow<List<HiddenMedia>>

    @Query("SELECT uri FROM hidden_media")
    suspend fun getHiddenUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(items: List<HiddenMedia>)

    @Query("DELETE FROM hidden_media WHERE uri IN (:uris)")
    suspend fun unhide(uris: List<String>)
}
