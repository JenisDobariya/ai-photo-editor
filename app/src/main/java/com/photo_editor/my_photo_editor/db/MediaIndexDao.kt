package com.photo_editor.my_photo_editor.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaIndexDao {
    @Query("SELECT * FROM media_index")
    fun observeAll(): Flow<List<MediaIndex>>

    @Query("SELECT COUNT(*) FROM media_index")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM media_index")
    suspend fun getAll(): List<MediaIndex>

    @Query("SELECT uri, dateAdded, size, labels FROM media_index")
    suspend fun getKeys(): List<MediaIndexKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<MediaIndex>)

    @Query("DELETE FROM media_index WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)
}
