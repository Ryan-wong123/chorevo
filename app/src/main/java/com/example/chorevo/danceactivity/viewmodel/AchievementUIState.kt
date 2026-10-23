package com.example.chorevo.danceactivity.viewmodel

import com.example.chorevo.danceactivity.AchievementLevel
import com.example.chorevo.danceactivity.BadgeDefinition
import com.example.chorevo.danceactivity.BadgeId
import com.example.chorevo.danceactivity.levelForPoints
import com.example.chorevo.danceactivity.nextLevelForPoints


data class AchievementUiState(
    val loading: Boolean = false,

    val earnedBadgeIds: Set<BadgeId> = emptySet(),
    val earnedBadges: List<BadgeDefinition> = emptyList(),
    val newlyUnlocked: List<BadgeDefinition> = emptyList(),

    val sessionPoints: Int = 0,
    val totalPoints: Int = 0,

    val currentLevel: AchievementLevel = levelForPoints(0),
    val previousLevel: AchievementLevel = levelForPoints(0),
    val leveledUp: Boolean = false,

    val showAchievementDialog: Boolean = false,
    /** The session hasn't synced yet, so its points aren't known (offline). */
    val rewardsPending: Boolean = false,
    /** The daily limit of rewarded sessions was reached; this session earned nothing. */
    val rewardsCapped: Boolean = false,
    val errorMessage: String? = null
) {
    val nextLevel: AchievementLevel?
        get() = nextLevelForPoints(totalPoints)

    val progressToNextLevel: Float
        get() {
            val next = nextLevel ?: return 1f
            val range = (next.pointsRequired - currentLevel.pointsRequired).coerceAtLeast(1)
            val progress = totalPoints - currentLevel.pointsRequired
            return (progress.toFloat() / range.toFloat()).coerceIn(0f, 1f)
        }
}