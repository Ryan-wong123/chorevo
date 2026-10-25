package com.example.chorevo.core.database.di

import android.content.Context
import androidx.room.Room
import com.example.chorevo.core.database.AppDb
import com.example.chorevo.danceactivity.repository.DanceSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** Provides app db. */
    @Provides
    @Singleton
    fun provideAppDb(
        @ApplicationContext context: Context
    ): AppDb {
        return Room.databaseBuilder(
            context,
            AppDb::class.java,
            "app.db"
        )
            .addMigrations(AppDb.MIGRATION_5_6)
            // Only pre-release development versions (1–4) and downgrades are
            // wiped; any other missing migration fails loudly instead of
            // deleting users' unsynced sessions.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }

    /** Provides dance session dao. */
    @Provides
    fun provideDanceSessionDao(db: AppDb): DanceSessionDao {
        return db.danceSessionDao()
    }
}
