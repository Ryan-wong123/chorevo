package com.example.chorevo.danceactivity

/**
 * What one practice session earned, computed by the rewardsFromSession Cloud
 * Function (functions/rewards.js) and stored at users/{uid}/sessionRewards/{sessionId}.
 */
data class SessionRewards(
    val sessionId: String,
    /** True when the daily limit of rewarded sessions was reached: nothing was earned. */
    val capped: Boolean = false,
    val sessionPoints: Int = 0,
    /** Points from weekly challenges (and the all-three bonus) this session completed. */
    val challengePoints: Int = 0,
    val totalPointsBefore: Int = 0,
    val totalPointsAfter: Int = 0,
    val newBadges: List<BadgeId> = emptyList(),
    val completedChallenges: List<CompletedChallenge> = emptyList(),
    val bonusUnlocked: Boolean = false,
    val bonusPoints: Int = 0
)

data class CompletedChallenge(
    val id: String,
    val title: String,
    val emoji: String,
    val rewardPoints: Int
)

/** Parses the stored summary (unknown badges are skipped). */
fun sessionRewardsFrom(sessionId: String, data: Map<String, Any?>): SessionRewards {
    fun int(key: String) = (data[key] as? Number)?.toInt() ?: 0
    val badges = (data["newBadges"] as? List<*>).orEmpty().mapNotNull { name ->
        (name as? String)?.let { runCatching { BadgeId.valueOf(it) }.getOrNull() }
    }
    val challenges = (data["completedChallenges"] as? List<*>).orEmpty().mapNotNull { raw ->
        val c = raw as? Map<*, *> ?: return@mapNotNull null
        CompletedChallenge(
            id = c["id"] as? String ?: return@mapNotNull null,
            title = c["title"] as? String ?: "",
            emoji = c["emoji"] as? String ?: "",
            rewardPoints = (c["rewardPoints"] as? Number)?.toInt() ?: 0
        )
    }
    return SessionRewards(
        sessionId = sessionId,
        capped = data["capped"] as? Boolean ?: false,
        sessionPoints = int("sessionPoints"),
        challengePoints = int("challengePoints"),
        totalPointsBefore = int("totalPointsBefore"),
        totalPointsAfter = int("totalPointsAfter"),
        newBadges = badges,
        completedChallenges = challenges,
        bonusUnlocked = data["bonusUnlocked"] as? Boolean ?: false,
        bonusPoints = int("bonusPoints")
    )
}
