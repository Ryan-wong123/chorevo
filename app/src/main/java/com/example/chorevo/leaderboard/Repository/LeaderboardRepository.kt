package com.example.chorevo.leaderboard.Repository

import android.util.Log
import com.example.chorevo.leaderboard.LeaderboardEntry
import com.example.chorevo.leaderboard.LeaderboardMetric
import com.example.chorevo.leaderboard.LeaderboardScope
import com.example.chorevo.leaderboard.LeaderboardStat
import com.example.chorevo.leaderboard.LeaderboardStatsSnapshot
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LeaderboardRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    /** Observes leaderboard. */
    fun observeLeaderboard(
        scope: LeaderboardScope,
        metric: LeaderboardMetric,
        myUid: String
    ): Flow<List<LeaderboardEntry>> {
        val allStatsFlow = observeAllStats(metric)

        return when (scope) {
            LeaderboardScope.GLOBAL -> {
                allStatsFlow.map { stats ->
                    stats.toRanked(metric = metric, myUid = myUid)
                }
            }

            LeaderboardScope.FRIENDS -> {
                combine(allStatsFlow, observeFriendIds(myUid)) { allStats, friendIds ->
                    val allowed = friendIds + myUid
                    allStats
                        .filter { it.uid in allowed }
                        .toRanked(metric = metric, myUid = myUid)
                }
            }
        }
    }

    /** Refreshes user stats. */
    suspend fun refreshUserStats(uid: String, totalActiveDays: Int) {
        refreshUserStats(
            uid = uid,
            stats = buildLeaderboardStatsSnapshot(
                uid = uid,
                totalActiveDays = totalActiveDays
            )
        )
    }

    /** Refreshes user stats. */
    private suspend fun refreshUserStats(
        uid: String,
        stats: LeaderboardStatsSnapshot
    ) {
        db.collection("leaderboardStats")
            .document(uid)
            .set(
                mapOf(
                    "uid" to uid,
                    "displayName" to stats.displayName.trim().takeUnless { it.isBlank() }.orEmpty().ifBlank { "User" },
                    "photoUrl" to stats.photoUrl,
                    "totalPracticeSessions" to stats.totalPracticeSessions,
                    "avgSimilarityScore" to stats.avgSimilarityScore,
                    // Keep the legacy field populated until existing leaderboard docs are migrated.
                    "streakDays" to stats.totalActiveDays,
                    "totalActiveDays" to stats.totalActiveDays,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()
    }

    /** Builds leaderboard stats snapshot. */
    private suspend fun buildLeaderboardStatsSnapshot(
        uid: String,
        totalActiveDays: Int
    ): LeaderboardStatsSnapshot {
        val userSnap = db.collection("users")
            .document(uid)
            .get()
            .await()

        val sessionSnap = db.collection("users")
            .document(uid)
            .collection("danceLearningSessions")
            .get()
            .await()

        val totalSessions = sessionSnap.size()
        val avgSimilarity = if (totalSessions == 0) {
            0.0
        } else {
            sessionSnap.documents
                .map { it.getDouble("alignmentScore") ?: 0.0 }
                .average()
        }

        return LeaderboardStatsSnapshot(
            displayName = userSnap.getString("displayName")?.trim().orEmpty().ifBlank { "User" },
            photoUrl = userSnap.getString("photoUrl"),
            totalPracticeSessions = totalSessions,
            avgSimilarityScore = avgSimilarity,
            totalActiveDays = totalActiveDays
        )
    }

    /** Observes all stats. */
    private fun observeAllStats(
        metric: LeaderboardMetric
    ): Flow<List<LeaderboardStat>> = callbackFlow {
        val reg = db.collection("leaderboardStats")
            .orderBy(metric.firestoreField, Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("LeaderboardRepository", "observeAllStats failed: ${err.message}", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val stats = snap?.documents.orEmpty()
                    .mapNotNull { it.toLeaderboardStatOrNull() }

                trySend(stats)
            }

        awaitClose { reg.remove() }
    }
     /** Observes friend ids. */
     fun observeFriendIds(
        myUid: String
    ): Flow<Set<String>> = callbackFlow {
         val reg = db.collection("users")
             .document(myUid)
             .collection("friends")
             .addSnapshotListener { snap, err ->
                 if (err != null) {
                     Log.e("LeaderboardRepository", "observeFriendIds failed: ${err.message}", err)
                     trySend(emptySet())
                     return@addSnapshotListener
                 }

                 val ids = snap?.documents.orEmpty()
                     .map { it.id }
                     .toSet()

                 trySend(ids)
             }

         awaitClose { reg.remove() }
     }
    /** Adds friend. */
    suspend fun addFriend(
        myUid: String,
        friendUid: String,
        displayName: String,
        photoUrl: String?
    ) {
        if (myUid.isBlank() || friendUid.isBlank() || myUid == friendUid) return

        db.collection("users")
            .document(myUid)
            .collection("friends")
            .document(friendUid)
            .set(
                mapOf(
                    "uid" to friendUid,
                    "displayName" to displayName,
                    "photoUrl" to photoUrl,
                    "addedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()
    }
    /** Removes friend. */
    suspend fun removeFriend(
        myUid: String,
        friendUid: String
    ) {
        if (myUid.isBlank() || friendUid.isBlank()) return

        db.collection("users")
            .document(myUid)
            .collection("friends")
            .document(friendUid)
            .delete()
            .await()
    }
    private fun DocumentSnapshot.toLeaderboardStatOrNull(): LeaderboardStat? {
        val uid = getString("uid") ?: id
        val displayName = getString("displayName")?.trim().takeUnless { it.isNullOrBlank() } ?: "User"
        val photoUrl = getString("photoUrl")
        val totalPracticeSessions = (getLong("totalPracticeSessions") ?: 0L).toInt()
        val avgSimilarityScore = getDouble("avgSimilarityScore") ?: 0.0
        val totalActiveDays = (getLong("totalActiveDays") ?: getLong("streakDays") ?: 0L).toInt()

        return LeaderboardStat(
            uid = uid,
            displayName = displayName,
            photoUrl = photoUrl,
            totalPracticeSessions = totalPracticeSessions,
            avgSimilarityScore = avgSimilarityScore,
            totalActiveDays = totalActiveDays
        )
    }

    private fun List<LeaderboardStat>.toRanked(
        metric: LeaderboardMetric,
        myUid: String
    ): List<LeaderboardEntry> {
        val sorted = this.sortedWith(
            compareByDescending<LeaderboardStat> { metric.scoreOf(it) }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.uid }
        )

        var lastScore: Double? = null
        var lastRank = 0

        return sorted.mapIndexed { index, stat ->
            val score = metric.scoreOf(stat)
            val sameAsPrevious = lastScore?.let { abs(it - score) < 0.0001 } ?: false
            val rank = if (sameAsPrevious) lastRank else index + 1

            lastScore = score
            lastRank = rank

            LeaderboardEntry(
                rank = rank,
                uid = stat.uid,
                displayName = stat.displayName,
                photoUrl = stat.photoUrl,
                totalPracticeSessions = stat.totalPracticeSessions,
                avgSimilarityScore = stat.avgSimilarityScore,
                totalActiveDays = stat.totalActiveDays,
                isMe = stat.uid == myUid
            )
        }
    }
}
