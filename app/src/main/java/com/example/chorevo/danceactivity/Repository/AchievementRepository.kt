package com.example.chorevo.danceactivity.Repository

import com.example.chorevo.danceactivity.BadgeDefinition
import com.example.chorevo.danceactivity.BadgeId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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
 * Firestore path: users/{uid}/achievements/current
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

    /** Handles award badges. */
    suspend fun awardBadges(uid: String, newBadges: List<BadgeId>): Set<BadgeId> {
        if (newBadges.isEmpty()) return getEarnedBadges(uid)

        val ref = achievementsRef(uid)
        var resultSet: Set<BadgeId> = emptySet()

        db.runTransaction { tx ->
            val snap = tx.get(ref)

            @Suppress("UNCHECKED_CAST")
            val existing = (snap.get("earnedBadges") as? List<String>)
                ?.mapNotNull { runCatching { BadgeId.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: emptySet()

            resultSet = existing + newBadges

            tx.set(
                ref,
                mapOf(
                    "earnedBadges" to resultSet.map { it.name },
                    "lastUpdated" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            null
        }.await()

        return resultSet
    }

    /** Handles award and get new. */
    suspend fun awardAndGetNew(uid: String, newBadges: List<BadgeId>): List<BadgeDefinition> {
        if (newBadges.isEmpty()) return emptyList()

        awardBadges(uid, newBadges)

        return newBadges.mapNotNull { id ->
            ALL_BADGES.firstOrNull { it.id == id }
        }
    }

    /** Returns earned badge definitions. */
    suspend fun getEarnedBadgeDefinitions(uid: String): List<BadgeDefinition> {
        val earnedIds = getEarnedBadges(uid)
        return ALL_BADGES.filter { it.id in earnedIds }
    }
}
