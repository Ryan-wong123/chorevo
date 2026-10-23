package com.example.chorevo.danceactivity.repository

import com.example.chorevo.danceactivity.BadgeDefinition
import com.example.chorevo.danceactivity.BadgeId
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.example.chorevo.danceactivity.ALL_BADGES
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore path:  users/{uid}/achievements/current
 *
 * Fields:
 *   earnedBadges  – List<String>  (BadgeId names)
 *   lastUpdated   – server timestamp
 */
/**
 * Badges are awarded by the rewardsFromSession Cloud Function
 * (functions/rewards.js); the app only reads them.
 */

@Singleton
class AchievementRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    /** Handles achievements ref. */
    private fun achievementsRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("achievements")
            .document("current")

    /** Returns earned badges. */
    suspend fun getEarnedBadges(uid: String): Set<BadgeId> {
        val snap = achievementsRef(uid).get().await()
        if (!snap.exists()) return emptySet()

        @Suppress("UNCHECKED_CAST")
        val raw = snap.get("earnedBadges") as? List<String> ?: return emptySet()

        return raw.mapNotNull { name ->
            runCatching { BadgeId.valueOf(name) }.getOrNull()
        }.toSet()
    }

    /** Returns earned badge definitions. */
    suspend fun getEarnedBadgeDefinitions(uid: String): List<BadgeDefinition> {
        val earnedIds = getEarnedBadges(uid)
        return ALL_BADGES.filter { it.id in earnedIds }
    }
}
