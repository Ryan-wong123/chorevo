package com.example.chorevo.danceactivity.Repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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


@Singleton
class FirebaseStreakRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    /** Handles monday of. */
    private fun mondayOf(date: LocalDate): LocalDate {
        val diff = (date.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /** Handles day index mon0. */
    private fun dayIndexMon0(date: LocalDate): Int = date.dayOfWeek.value - 1
    /** Checks whether day done. */
    private fun isDayDone(mask: Int, idx: Int): Boolean = ((mask shr idx) and 1) == 1
    /** Updates day done. */
    private fun setDayDone(mask: Int, idx: Int): Int = mask or (1 shl idx)

    /** Handles streak ref. */
    private fun streakRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("streaks")
            .document("current")

    /** Returns weekly streak. */
    suspend fun getWeeklyStreak(
        uid: String,
        autoResetIfNewWeek: Boolean = true
    ): WeeklyStreak {
        val ref = streakRef(uid)
        val snap = ref.get().await()

        val today = LocalDate.now()
        val thisWeekStart = mondayOf(today).toString()

        val current = if (snap.exists()) {
            WeeklyStreak(
                weekStart = snap.getString("weekStart") ?: thisWeekStart,
                daysMask = (snap.getLong("daysMask") ?: 0L).toInt(),
                totalStreakActivities = (snap.getLong("totalStreakActivities") ?: 0L).toInt(),
                lastActivityDate = snap.getString("lastActivityDate")
            )
        } else {
            WeeklyStreak(weekStart = thisWeekStart)
        }

        if (current.weekStart != thisWeekStart) {
            val reset = current.copy(
                weekStart = thisWeekStart,
                daysMask = 0
            )

            if (autoResetIfNewWeek) {
                ref.set(
                    mapOf(
                        "weekStart" to reset.weekStart,
                        "daysMask" to reset.daysMask
                    ),
                    SetOptions.merge()
                ).await()
            }

            return reset
        }

        if (!snap.exists()) {
            ref.set(
                mapOf(
                    "weekStart" to current.weekStart,
                    "daysMask" to current.daysMask,
                    "totalStreakActivities" to current.totalStreakActivities
                ),
                SetOptions.merge()
            ).await()
        }

        return current
    }

    /** Handles tick today. */
    suspend fun tickToday(uid: String): WeeklyStreak {
        val ref = streakRef(uid)
        val today = LocalDate.now()
        val thisWeekStart = mondayOf(today).toString()
        val idx = dayIndexMon0(today)

        return db.runTransaction { tx ->
            val snap = tx.get(ref)

            val weekStart = snap.getString("weekStart") ?: thisWeekStart
            val storedMask = (snap.getLong("daysMask") ?: 0L).toInt()
            val storedTotal = (snap.getLong("totalStreakActivities") ?: 0L).toInt()

            val baseMask = if (weekStart == thisWeekStart) storedMask else 0
            val alreadyDone = isDayDone(baseMask, idx)

            val newMask = if (alreadyDone) baseMask else setDayDone(baseMask, idx)
            val newTotal = if (alreadyDone) storedTotal else storedTotal + 1

            tx.set(
                ref,
                mapOf(
                    "weekStart" to thisWeekStart,
                    "daysMask" to newMask,
                    "totalStreakActivities" to newTotal,
                    "lastActivityDate" to today.toString(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            WeeklyStreak(
                weekStart = thisWeekStart,
                daysMask = newMask,
                totalStreakActivities = newTotal,
                lastActivityDate = today.toString()
            )
        }.await()
    }
}
