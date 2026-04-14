package com.example.chorevo.core.Database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.danceactivity.DanceSessionEntity


@Database(
    entities = [DanceSessionEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    /** Handles dance session dao. */
    abstract fun danceSessionDao(): DanceSessionDao
}
