package com.photo_editor.my_photo_editor.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedDate DESC")
    fun getAllFavorites(): Flow<List<FavoriteMedia>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteMedia)

    @Delete
    suspend fun removeFavorite(favorite: FavoriteMedia)

    @Query("SELECT EXISTS(SELECT * FROM favorites WHERE uri = :uri)")
    suspend fun isFavorite(uri: String): Boolean

    @Query("SELECT * FROM favorites WHERE uri = :uri")
    suspend fun getFavorite(uri: String): FavoriteMedia?
}
