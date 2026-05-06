package com.example.chorevo.danceactivity.Repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Persists lifetime points to Firestore at:
 *   users/{uid}/points/current
 *
 * Fields stored:
 *   totalPoints        – all-time accumulated points (Int)
 *   lastSessionPoints  – points earned in the most recent session (Int)
 *   lastUpdated        – server timestamp
 *
 * Points formula (per session):
 *   alignment  * 50
 *   timing     * 30
 *   smoothness * 20
 *   ──────────────
 *   max 100 pts / session
 */

data class PointsSnapshot(
    val totalPoints: Int = 0,
    val lastSessionPoints: Int = 0
)
class PointsRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    /** Handles points ref. */
    private fun pointsRef(uid: String) =
        db.collection("users").document(uid)
            .collection("points").document("current")

    // ── Calculation ───────────────────────────────────────────────────────────


    /** Calculates session points. */
    fun calculateSessionPoints(
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ): Int {
        val raw = alignmentScore * 50.0 +
                timingConsistencyScore * 30.0 +
                smoothnessScore * 20.0
        return raw.toInt().coerceIn(0, 100)
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Returns points. */
    suspend fun getPoints(uid: String): PointsSnapshot {
        val snap = pointsRef(uid).get().await()
        return if (snap.exists()) {
            PointsSnapshot(
                totalPoints = (snap.getLong("totalPoints") ?: 0L).toInt(),
                lastSessionPoints = (snap.getLong("lastSessionPoints") ?: 0L).toInt()
            )
        } else {
            PointsSnapshot()
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Atomically adds [sessionPoints] to the running total and returns
     * the updated [PointsSnapshot].
     */
    suspend fun addSessionPoints(uid: String, sessionPoints: Int): PointsSnapshot {
        val ref = pointsRef(uid)
        var updatedTotal = 0

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = (snap.getLong("totalPoints") ?: 0L).toInt()
            updatedTotal = current + sessionPoints

            tx.set(
                ref,
                mapOf(
                    "totalPoints" to updatedTotal,
                    "lastSessionPoints" to sessionPoints,
                    "lastUpdated" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            null
        }.await()

        return PointsSnapshot(
            totalPoints = updatedTotal,
            lastSessionPoints = sessionPoints
        )
    }
}
