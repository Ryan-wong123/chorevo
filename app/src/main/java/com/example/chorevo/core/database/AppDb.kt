package com.example.chorevo.core.database

import android.content.Context
import androidx.room.database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chorevo.danceactivity.repository.DanceSessionDao
import com.example.chorevo.danceactivity.DanceSessionEntity


@Database(
    entities = [DanceSessionEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    /** Handles dance session dao. */
    abstract fun danceSessionDao(): DanceSessionDao

    companion object {
        /** v6 adds dance_sessions.ownerUid so unsynced sessions are retried for the right account. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dance_sessions ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
