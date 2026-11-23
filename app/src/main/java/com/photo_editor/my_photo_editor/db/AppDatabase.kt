package com.photo_editor.my_photo_editor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FavoriteMedia::class, HiddenMedia::class, MediaIndex::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun mediaIndexDao(): MediaIndexDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v2 adds the Locked Folder table. Favorites are preserved. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hidden_media` (" +
                            "`uri` TEXT NOT NULL, " +
                            "`hiddenDate` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`uri`))"
                )
            }
        }

        /** v3 adds the on-device photo analysis used by Smart Search and the cleaner. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_index` (" +
                            "`uri` TEXT NOT NULL, " +
                            "`dateAdded` INTEGER NOT NULL, " +
                            "`size` INTEGER NOT NULL, " +
                            "`width` INTEGER NOT NULL, " +
                            "`height` INTEGER NOT NULL, " +
                            "`dHash` INTEGER NOT NULL, " +
                            "`sharpness` REAL NOT NULL, " +
                            "`labels` TEXT, " +
                            "`indexedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`uri`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photo_editor_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
