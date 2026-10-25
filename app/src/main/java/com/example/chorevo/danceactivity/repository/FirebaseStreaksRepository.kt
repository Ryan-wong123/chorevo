package com.example.chorevo.danceactivity.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class WeeklyStreak(
    val weekStart: String = "",          // "YYYY-MM-DD" Monday of the week
    val daysMask: Int = 0,               // 7-bit mask (Mon..Sun)
    val totalStreakActivities: Int = 0,  // all-time total completed days
    val lastActivityDate: String? = null // "YYYY-MM-DD"
)


/**
 * Reads the weekly streak at users/{uid}/streaks/current. The streak is kept
 * by the rewardsFromSession Cloud Function from synced practice sessions
 * (functions/rewards.js); the app can't write it.
 */
@Singleton
class FirebaseStreakRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    /** Handles monday of. */
    private fun mondayOf(date: LocalDate): LocalDate {
        val diff = (date.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /** Handles streak ref. */
    private fun streakRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("streaks")
            .document("current")

    /**
     * The user's streak for this week. A stored week that has ended shows as
     * an empty week (the total carries on).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getWeeklyStreak(
        uid: String,
        autoResetIfNewWeek: Boolean = true
    ): WeeklyStreak {
        val snap = streakRef(uid).get().await()
        val thisWeekStart = mondayOf(LocalDate.now()).toString()
        if (!snap.exists()) return WeeklyStreak(weekStart = thisWeekStart)

        val stored = WeeklyStreak(
            weekStart = snap.getString("weekStart") ?: thisWeekStart,
            daysMask = (snap.getLong("daysMask") ?: 0L).toInt(),
            totalStreakActivities = (snap.getLong("totalStreakActivities") ?: 0L).toInt(),
            lastActivityDate = snap.getString("lastActivityDate")
        )
        return if (stored.weekStart == thisWeekStart) stored else stored.copy(weekStart = thisWeekStart, daysMask = 0)
    }
}
