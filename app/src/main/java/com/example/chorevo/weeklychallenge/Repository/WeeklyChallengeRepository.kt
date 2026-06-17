package com.example.chorevo.weeklychallenge.Repository

import com.example.chorevo.danceactivity.Repository.PointsRepository
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.RecentSessionScore
import com.example.chorevo.weeklychallenge.ScoreMetric
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeEngine
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
import com.example.chorevo.weeklychallenge.WeeklyChallengeUpdateResult
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore path: users/{uid}/weeklyChallenges/{weekStart}
 *
 * Each calendar week (Monday start, matching [com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository])
 * gets its own document, generated lazily the first time it is requested. Reward
 * points for completed challenges are credited through the existing [PointsRepository]
 * ledger so weekly challenges feed the same level-up/points total already shown on
 * the profile screen instead of creating a second, disconnected currency.
 */
@Singleton
class WeeklyChallengeRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val danceLibraryRepository: DanceLibraryRepository,
    private val pointsRepository: PointsRepository
) {

    companion object {
        private const val GENERATION_SESSION_LOOKBACK = 40L
        private const val FOCUS_SCORE_LOOKBACK = 10
        private const val MOMENTUM_LOOKBACK_WEEKS = 4
    }

    private data class SessionStatsRow(
        val tsStart: Long,
        val alignmentScore: Double,
        val timingConsistencyScore: Double,
        val smoothnessScore: Double
    )

    /** Handles weekly challenges ref. */
    private fun weeklyChallengesRef(uid: String, weekStart: String) =
        db.collection("users")
            .document(uid)
            .collection("weeklyChallenges")
            .document(weekStart)

    /** Handles monday of. */
    private fun mondayOf(date: LocalDate): LocalDate {
        val diff = (date.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /** Returns current week start. */
    private fun currentWeekStart(): String = mondayOf(LocalDate.now()).toString()

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns this week's challenge set, generating and persisting a fresh one
     * the first time it is requested for the current week. An existing set for
     * the same week is returned as-is so in-progress challenges are never reset.
     */
    suspend fun getOrCreateCurrentWeek(uid: String): WeeklyChallengeSet {
        val weekStart = currentWeekStart()
        val ref = weeklyChallengesRef(uid, weekStart)

        val existing = ref.get().await().toWeeklyChallengeSetOrNull()
        if (existing != null) return existing

        val generated = generateFreshSet(uid, weekStart)
        ref.set(generated.toFirestoreMap(), SetOptions.merge()).await()
        return generated
    }

    /**
     * Folds one freshly completed practice session into this week's challenge
     * set and credits [PointsRepository] for any challenge (and the all-three
     * bonus) that newly completed as a result.
     */
    suspend fun recordCompletedSession(
        uid: String,
        danceType: String,
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ): WeeklyChallengeUpdateResult {
        val weekStart = currentWeekStart()
        val ref = weeklyChallengesRef(uid, weekStart)

        // Ensure the week's document exists before the fast transactional update below.
        val baseline = getOrCreateCurrentWeek(uid)

        val result = db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = snap.toWeeklyChallengeSetOrNull() ?: baseline

            val update = WeeklyChallengeEngine.recordSession(
                set = current,
                danceType = danceType,
                alignmentScore = alignmentScore,
                timingConsistencyScore = timingConsistencyScore,
                smoothnessScore = smoothnessScore
            )

            tx.set(ref, update.challengeSet.toFirestoreMap(), SetOptions.merge())
            update
        }.await()

        val rewardTotal = result.newlyCompleted.sumOf { it.rewardPoints } +
            if (result.bonusNewlyUnlocked) result.challengeSet.bonusRewardPoints else 0

        if (rewardTotal > 0) {
            runCatching { pointsRepository.addSessionPoints(uid, rewardTotal) }
        }

        return result
    }

    // ── Generation inputs ───────────────────────────────────────────────────

    private suspend fun generateFreshSet(uid: String, weekStart: String): WeeklyChallengeSet {
        val statsRows = fetchRecentSessionStats(uid)

        val recentScores = statsRows.take(FOCUS_SCORE_LOOKBACK).map {
            RecentSessionScore(
                alignmentScore = it.alignmentScore,
                timingConsistencyScore = it.timingConsistencyScore,
                smoothnessScore = it.smoothnessScore
            )
        }

        val recentWeeklySessionCounts = bucketByCompletedWeek(statsRows, excludeWeekStart = weekStart)

        val availableDanceTypes = danceLibraryRepository.listEnabled()
            .getOrElse { emptyList() }
            .map { it.danceType }

        return WeeklyChallengeEngine.generate(
            weekStart = weekStart,
            recentScores = recentScores,
            availableDanceTypes = availableDanceTypes,
            recentWeeklySessionCounts = recentWeeklySessionCounts
        )
    }

    /** Returns recent session stats, most recent first. */
    private suspend fun fetchRecentSessionStats(uid: String): List<SessionStatsRow> {
        val snap = db.collection("users")
            .document(uid)
            .collection("danceLearningSessions")
            .orderBy("tsStart", Query.Direction.DESCENDING)
            .limit(GENERATION_SESSION_LOOKBACK)
            .get()
            .await()

        return snap.documents.mapNotNull { doc ->
            val tsStart = doc.getLong("tsStart") ?: return@mapNotNull null
            val alignment = doc.getDouble("alignmentScore") ?: return@mapNotNull null
            val timing = doc.getDouble("timingConsistencyScore") ?: 0.0
            val smoothness = doc.getDouble("smoothnessScore") ?: 0.0
            SessionStatsRow(tsStart, alignment, timing, smoothness)
        }
    }

    /** Buckets fully-completed prior weeks' session counts, oldest first. */
    private fun bucketByCompletedWeek(rows: List<SessionStatsRow>, excludeWeekStart: String): List<Int> {
        val counts = sortedMapOf<String, Int>()

        rows.forEach { row ->
            val date = Instant.ofEpochMilli(row.tsStart).atZone(ZoneId.systemDefault()).toLocalDate()
            val week = mondayOf(date).toString()
            if (week == excludeWeekStart) return@forEach
            counts[week] = (counts[week] ?: 0) + 1
        }

        return counts.values.toList().takeLast(MOMENTUM_LOOKBACK_WEEKS)
    }

    // ── Firestore (de)serialization ─────────────────────────────────────────

    private fun WeeklyChallengeSet.toFirestoreMap(): Map<String, Any?> = mapOf(
        "weekStart" to weekStart,
        "allCompleted" to allCompleted,
        "bonusRewardPoints" to bonusRewardPoints,
        "bonusClaimed" to bonusClaimed,
        "updatedAt" to FieldValue.serverTimestamp(),
        "challenges" to challenges.associate { it.id to it.toFirestoreMap() }
    )

    private fun WeeklyChallenge.toFirestoreMap(): Map<String, Any?> = mapOf(
        "type" to type.name,
        "title" to title,
        "description" to description,
        "emoji" to emoji,
        "targetCount" to targetCount,
        "progressCount" to progressCount,
        "rewardPoints" to rewardPoints,
        "completed" to completed,
        "completedAt" to completedAt,
        "focusMetric" to focusMetric?.name,
        "focusThreshold" to focusThreshold,
        "requiredStyles" to requiredStyles,
        "satisfiedStyles" to satisfiedStyles
    )

    private fun DocumentSnapshot.toWeeklyChallengeSetOrNull(): WeeklyChallengeSet? {
        if (!exists()) return null
        val weekStart = getString("weekStart") ?: return null

        @Suppress("UNCHECKED_CAST")
        val challengesRaw = get("challenges") as? Map<String, Map<String, Any?>> ?: emptyMap()
        val challenges = challengesRaw.mapNotNull { (id, raw) -> raw.toWeeklyChallengeOrNull(id) }

        return WeeklyChallengeSet(
            weekStart = weekStart,
            challenges = challenges,
            allCompleted = getBoolean("allCompleted") ?: false,
            bonusRewardPoints = (getLong("bonusRewardPoints") ?: WeeklyChallengeEngine.BONUS_REWARD_POINTS.toLong()).toInt(),
            bonusClaimed = getBoolean("bonusClaimed") ?: false
        )
    }

    private fun Map<String, Any?>.toWeeklyChallengeOrNull(id: String): WeeklyChallenge? {
        val type = (this["type"] as? String)?.let {
            runCatching { ChallengeType.valueOf(it) }.getOrNull()
        } ?: return null

        val focusMetric = (this["focusMetric"] as? String)?.let {
            runCatching { ScoreMetric.valueOf(it) }.getOrNull()
        }

        return WeeklyChallenge(
            id = id,
            type = type,
            title = this["title"] as? String ?: "",
            description = this["description"] as? String ?: "",
            emoji = this["emoji"] as? String ?: "",
            targetCount = this["targetCount"].asInt(),
            progressCount = this["progressCount"].asInt(),
            rewardPoints = this["rewardPoints"].asInt(),
            completed = this["completed"] as? Boolean ?: false,
            completedAt = (this["completedAt"] as? Number)?.toLong(),
            focusMetric = focusMetric,
            focusThreshold = (this["focusThreshold"] as? Number)?.toDouble(),
            requiredStyles = (this["requiredStyles"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            satisfiedStyles = (this["satisfiedStyles"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        )
    }

    private fun Any?.asInt(): Int = (this as? Number)?.toInt() ?: 0
}
