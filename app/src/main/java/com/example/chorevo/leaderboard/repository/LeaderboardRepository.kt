package com.example.chorevo.leaderboard.repository

import android.util.Log
import com.example.chorevo.leaderboard.LeaderboardEntry
import com.example.chorevo.leaderboard.LeaderboardMetric
import com.example.chorevo.leaderboard.LeaderboardScope
import com.example.chorevo.leaderboard.LeaderboardStat
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LeaderboardRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    private companion object {
        /** Max values in a Firestore `in` filter. */
        const val FIRESTORE_IN_LIMIT = 30
    }

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

            // Friends are read directly by uid, so a friend outside the global
            // top 100 still shows up.
            LeaderboardScope.FRIENDS -> {
                observeFriendIds(myUid)
                    .flatMapLatest { friendIds -> observeStatsFor(friendIds + myUid) }
                    .map { stats -> stats.toRanked(metric = metric, myUid = myUid) }
            }
        }
    }

    /**
     * The signed-in user's global rank for [metric], even when they are outside
     * the top 100: rank = 1 + number of users with a strictly higher score
     * (a server-side count(), so ties share a rank like the list does).
     * Emits null when the user has no stats yet.
     */
    fun observeMyGlobalEntry(
        metric: LeaderboardMetric,
        myUid: String
    ): Flow<LeaderboardEntry?> = callbackFlow {
        val reg = db.collection("leaderboardStats")
            .document(myUid)
            .addSnapshotListener { doc, err ->
                if (err != null || doc == null || !doc.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val stat = doc.toLeaderboardStatOrNull() ?: run {
                    trySend(null)
                    return@addSnapshotListener
                }
                val myScore = doc.getDouble(metric.firestoreField) ?: metric.scoreOf(stat)
                launch {
                    val higher = runCatching {
                        db.collection("leaderboardStats")
                            .whereGreaterThan(metric.firestoreField, myScore)
                            .count()
                            .get(AggregateSource.SERVER)
                            .await()
                            .count
                    }.getOrNull() ?: return@launch
                    trySend(
                        LeaderboardEntry(
                            rank = higher.toInt() + 1,
                            uid = stat.uid,
                            displayName = stat.displayName,
                            photoUrl = stat.photoUrl,
                            totalPracticeSessions = stat.totalPracticeSessions,
                            avgSimilarityScore = stat.avgSimilarityScore,
                            totalActiveDays = stat.totalActiveDays,
                            isMe = true
                        )
                    )
                }
            }
        awaitClose { reg.remove() }
    }

    /**
     * Live stats for specific users. Firestore `in` filters take at most 30
     * values, so the ids are split into chunks of 30 and the results merged.
     * Users with no stats document yet are left out.
     */
    private fun observeStatsFor(uids: Set<String>): Flow<List<LeaderboardStat>> {
        val chunks = uids.filter { it.isNotBlank() }.chunked(FIRESTORE_IN_LIMIT)
        if (chunks.isEmpty()) return flowOf(emptyList())
        val flows = chunks.map { chunk ->
            callbackFlow {
                val reg = db.collection("leaderboardStats")
                    .whereIn(FieldPath.documentId(), chunk)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            Log.e("LeaderboardRepository", "observeStatsFor failed: ${err.message}", err)
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        trySend(snap?.documents.orEmpty().mapNotNull { it.toLeaderboardStatOrNull() })
                    }
                awaitClose { reg.remove() }
            }
        }
        return combine(flows) { parts -> parts.flatMap { it } }
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
