package com.example.chorevo.danceactivity.repository

import com.google.firebase.firestore.FirebaseFirestore
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
 * Points are awarded by the rewardsFromSession Cloud Function from each
 * synced session (functions/rewards.js: alignment × 50 + timing × 30 +
 * smoothness × 20, at most 100 per session, plus weekly challenge rewards).
 * The app only reads them.
 */

data class PointsSnapshot(
    val totalPoints: Int = 0,
    val lastSessionPoints: Int = 0
)
@Singleton
class PointsRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    /** Handles points ref. */
    private fun pointsRef(uid: String) =
        db.collection("users").document(uid)
            .collection("points").document("current")

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
}
