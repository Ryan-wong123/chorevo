package com.example.chorevo.weeklychallenge.repository

import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.ScoreMetric
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads this week's challenges at users/{uid}/weeklyChallenges/{weekStart}
 * (weeks start on Monday, in the phone's time zone).
 *
 * The server owns them: the weeklyChallenges callable function creates a
 * week's personalised set the first time it's needed, and rewardsFromSession
 * advances them from each synced practice session and awards their points
 * (functions/rewards.js). The app can't write them.
 */
@Singleton
class WeeklyChallengeRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val functions: FirebaseFunctions
) {

    private companion object {
        const val DEFAULT_BONUS_REWARD_POINTS = 50L
    }

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

    /**
     * This week's challenge set. The first time in a week, asks the server to
     * create it; after that it's a plain read, so progress is never reset.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getOrCreateCurrentWeek(uid: String): WeeklyChallengeSet {
        val weekStart = currentWeekStart()
        weeklyChallengesRef(uid, weekStart).get().await().toWeeklyChallengeSetOrNull()?.let { return it }

        val offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val result = functions.getHttpsCallable("weeklyChallenges")
            .call(mapOf("tzOffsetMinutes" to offsetMinutes))
            .await()
        val serverWeek = (result.getData() as? Map<*, *>)?.get("weekStart") as? String ?: weekStart
        return weeklyChallengesRef(uid, serverWeek).get().await().toWeeklyChallengeSetOrNull()
            ?: WeeklyChallengeSet(weekStart = serverWeek)
    }

    // ── Firestore (de)serialization ─────────────────────────────────────────

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
            bonusRewardPoints = (getLong("bonusRewardPoints") ?: DEFAULT_BONUS_REWARD_POINTS).toInt(),
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
