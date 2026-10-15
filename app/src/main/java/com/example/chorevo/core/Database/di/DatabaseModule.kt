package com.example.chorevo.core.Database.di

import android.content.Context
import androidx.room.Room
import com.example.chorevo.core.Database.AppDb
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
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
            .fallbackToDestructiveMigration()
            .build()
    }

    /** Provides dance session dao. */
    @Provides
    fun provideDanceSessionDao(db: AppDb): DanceSessionDao {
        return db.danceSessionDao()
    }
}
