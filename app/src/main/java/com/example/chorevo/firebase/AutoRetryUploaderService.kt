package com.example.chorevo.firebase

import com.example.chorevo.core.Database.di.IoDispatcher
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

//Or better convert it to a function? if you do so remove the io dispatcher and qualifier
class AutoRetryUploaderService @Inject constructor(
    private val dao: DanceSessionDao,
    private val danceLibraryRepository: DanceLibraryRepository,
    private val streakRepository: FirebaseStreakRepository,
    private val leaderboardRepository: LeaderboardRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** Retries unsynced sessions. */
    suspend fun retryUnsyncedSessions(uid: String) = withContext(io) {
        val pending = dao.unsyncedFor(uid)
        if (pending.isEmpty()) return@withContext

        for (s in pending) {
            runCatching {
                // Points, badges, streak and the leaderboard are computed
                // server-side from the uploaded session.
                danceLibraryRepository.uploadDanceSession(uid = uid, session = s)
                dao.markSynced(s.sessionId)
            }
        }
    }
}
