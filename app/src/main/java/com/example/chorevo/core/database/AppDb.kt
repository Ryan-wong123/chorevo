package com.example.chorevo.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chorevo.danceactivity.repository.DanceSessionDao
import com.example.chorevo.danceactivity.DanceSessionEntity


/**
 * The local database (practice sessions waiting to sync). Every schema change
 * needs a version bump, a [Migration] added to DatabaseModule, and the new
 * exported schema in app/schemas committed; there is no destructive fallback,
 * so unsynced sessions are never wiped by an update.
 */
@Database(
    entities = [DanceSessionEntity::class],
    version = 6,
    exportSchema = true
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
