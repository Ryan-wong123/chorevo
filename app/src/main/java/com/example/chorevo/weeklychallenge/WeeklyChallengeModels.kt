package com.example.chorevo.weeklychallenge

/**
 * The three score axes captured by [com.example.chorevo.posedetection.PoseSimilarityEvaluator]
 * for every completed practice session. The Focus Drill challenge reads these to target
 * whichever axis the learner is currently weakest on, instead of a generic fixed goal.
 */
enum class ScoreMetric(val label: String) {
    ALIGNMENT("Alignment"),
    TIMING("Timing"),
    SMOOTHNESS("Smoothness")
}

enum class ChallengeType {
    FOCUS_DRILL,
    STYLE_EXPLORER,
    MOMENTUM_GOAL
}

/**
 * One slot inside this week's challenge set.
 *
 * [id] is a stable slot key ("focus_drill", "style_explorer", "momentum_goal") rather than
 * a list index, so progress survives re-reads of the same week's document even though the
 * set is generated lazily the first time it is needed each week.
 */
data class WeeklyChallenge(
    val id: String,
    val type: ChallengeType,
    val title: String,
    val description: String,
    val emoji: String,
    val targetCount: Int,
    val progressCount: Int = 0,
    val rewardPoints: Int,
    val completed: Boolean = false,
    val completedAt: Long? = null,

    // FOCUS_DRILL only: which score axis this challenge targets, and the threshold
    // (0.0..1.0) a session's score on that axis must reach for the session to count.
    val focusMetric: ScoreMetric? = null,
    val focusThreshold: Double? = null,

    // STYLE_EXPLORER only: the dance types that count toward this challenge, and
    // which of them the learner has already satisfied with a session this week.
    val requiredStyles: List<String> = emptyList(),
    val satisfiedStyles: List<String> = emptyList()
) {
    /** Handles progress fraction. */
    val progressFraction: Float
        get() = if (targetCount <= 0) 1f
        else (progressCount.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f)
}

/** The full personalized challenge slate for a single ISO week. */
data class WeeklyChallengeSet(
    val weekStart: String = "",
    val challenges: List<WeeklyChallenge> = emptyList(),
    val allCompleted: Boolean = false,
    val bonusRewardPoints: Int = 0,
    val bonusClaimed: Boolean = false
)
