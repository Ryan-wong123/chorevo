package com.example.chorevo.danceactivity


// ── Level Definitions ─────────────────────────────────────────────────────────

data class AchievementLevel(
    val level: Int,
    val title: String,
    val pointsRequired: Int,
    val emoji: String
)

val ACHIEVEMENT_LEVELS = listOf(
    AchievementLevel(1, "Beginner",      0,    "🌰"),
    AchievementLevel(2, "Mover",         200,  "🌱"),
    AchievementLevel(3, "Dancer",        500,  "🌿"),
    AchievementLevel(4, "Performer",     1000, "🌸"),
    AchievementLevel(5, "Choreographer", 2000, "🌳")
)

/** Handles level for points. */
fun levelForPoints(totalPoints: Int): AchievementLevel =
    ACHIEVEMENT_LEVELS.lastOrNull { totalPoints >= it.pointsRequired }
        ?: ACHIEVEMENT_LEVELS.first()

/** Handles next level for points. */
fun nextLevelForPoints(totalPoints: Int): AchievementLevel? {
    val current = levelForPoints(totalPoints)
    return ACHIEVEMENT_LEVELS.firstOrNull { it.level == current.level + 1 }
}

// ── Badge Definitions ─────────────────────────────────────────────────────────

enum class BadgeId {
    FIRST_STEPS,
    PERFECT_POSE,
    SMOOTH_OPERATOR,
    IN_THE_GROOVE,
    HIGH_SCORER,
    CENTURY,
    DEDICATED,
    ELITE,
    STREAK_STARTER,
    WEEK_WARRIOR
}

data class BadgeDefinition(
    val id: BadgeId,
    val name: String,
    val description: String,
    val emoji: String
)

val ALL_BADGES = listOf(
    BadgeDefinition(BadgeId.FIRST_STEPS,     "First Steps",     "Complete your first dance session",          "👣"),
    BadgeDefinition(BadgeId.PERFECT_POSE,    "Perfect Pose",    "Achieve 95%+ alignment in a session",        "🎯"),
    BadgeDefinition(BadgeId.SMOOTH_OPERATOR, "Smooth Operator", "Achieve 95%+ smoothness in a session",       "🌊"),
    BadgeDefinition(BadgeId.IN_THE_GROOVE,   "In the Groove",   "Achieve 95%+ timing in a session",           "🎵"),
    BadgeDefinition(BadgeId.HIGH_SCORER,     "High Scorer",     "Earn 90 or more points in a single session", "⭐"),
    BadgeDefinition(BadgeId.CENTURY,         "Century",         "Earn a perfect 100 points in one session",   "💯"),
    BadgeDefinition(BadgeId.DEDICATED,       "Dedicated",       "Accumulate 500 total points",                "🏅"),
    BadgeDefinition(BadgeId.ELITE,           "Elite",           "Accumulate 1000 total points",               "👑"),
    BadgeDefinition(BadgeId.STREAK_STARTER,  "Streak Starter",  "Practice on 3 different days in a week",     "🔥"),
    BadgeDefinition(BadgeId.WEEK_WARRIOR,    "Week Warrior",    "Complete all 7 days in a week",              "⚡")
)

// ── Evaluation Logic ─────────────────────────────────────────────────────────

/** Handles evaluate new badges. */
fun evaluateNewBadges(
    sessionPoints: Int,
    totalPointsAfter: Int,
    alignmentScore: Double,
    timingConsistencyScore: Double,
    smoothnessScore: Double,
    isFirstSession: Boolean,
    weekDaysMask: Int,
    previouslyEarned: Set<BadgeId>
): List<BadgeId> {
    val earned = mutableListOf<BadgeId>()

    /** Handles award. */
    fun award(id: BadgeId) {
        if (id !in previouslyEarned) earned.add(id)
    }

    if (isFirstSession)                 award(BadgeId.FIRST_STEPS)
    if (alignmentScore >= 0.95)         award(BadgeId.PERFECT_POSE)
    if (smoothnessScore >= 0.95)        award(BadgeId.SMOOTH_OPERATOR)
    if (timingConsistencyScore >= 0.95) award(BadgeId.IN_THE_GROOVE)
    if (sessionPoints >= 90)            award(BadgeId.HIGH_SCORER)
    if (sessionPoints >= 100)           award(BadgeId.CENTURY)
    if (totalPointsAfter >= 500)        award(BadgeId.DEDICATED)
    if (totalPointsAfter >= 1000)       award(BadgeId.ELITE)

    val daysCompleted = Integer.bitCount(weekDaysMask)
    if (daysCompleted >= 3)             award(BadgeId.STREAK_STARTER)
    if (daysCompleted >= 7)             award(BadgeId.WEEK_WARRIOR)

    return earned
}
