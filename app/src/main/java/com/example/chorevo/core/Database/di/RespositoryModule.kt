package com.example.chorevo.core.Database.di


import com.example.chorevo.analytics.repository.AnalyticsRepository
import com.example.chorevo.danceactivity.Repository.AchievementRepository
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.Repository.PointsRepository
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import com.google.android.datatransport.runtime.dagger.Provides
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /** Provides analytics repository. */
    @Provides
    @Singleton
    fun provideAnalyticsRepository(
        firestore: FirebaseFirestore
    ): AnalyticsRepository = AnalyticsRepository(firestore)

    /** Provides achievement repository. */
    @Provides
    @Singleton
    fun provideAchievementRepository(
        firestore: FirebaseFirestore
    ): AchievementRepository = AchievementRepository(firestore)

    /** Provides firebase streak repository. */
    @Provides
    @Singleton
    fun provideFirebaseStreakRepository(
        firestore: FirebaseFirestore
    ): FirebaseStreakRepository = FirebaseStreakRepository(firestore)

    /** Provides points repository. */
    @Provides
    @Singleton
    fun providePointsRepository(
        firestore: FirebaseFirestore
    ): PointsRepository = PointsRepository(firestore)

    /** Provides firebase auth repository. */
    @Provides
    @Singleton
    fun provideFirebaseAuthRepository(
        auth: FirebaseAuth,
        firestore: FirebaseFirestore,
        storage: FirebaseStorage
    ): FirebaseAuthRepository = FirebaseAuthRepository(auth, firestore, storage)

    /** Provides leaderboard repository. */
    @Provides
    @Singleton
    fun provideLeaderboardRepository(
        firestore: FirebaseFirestore
    ): LeaderboardRepository = LeaderboardRepository(db = firestore)

    /** Provides dance library repository. */
    @Provides
    @Singleton
    fun provideDanceLibraryRepository(
        firestore: FirebaseFirestore,
        storage: FirebaseStorage,
        danceSessionDao: DanceSessionDao
    ): DanceLibraryRepository = DanceLibraryRepository(
        firestore = firestore,
        storage = storage,
        danceSessionDao = danceSessionDao
    )

    /** Provides video uploads repository. */
    @Provides
    @Singleton
    fun provideVideoUploadsRepository(
        firestore: FirebaseFirestore,
        storage: FirebaseStorage
    ): VideoUploadsRepository = VideoUploadsRepository(
        db = firestore,
        storage = storage
    )
}
