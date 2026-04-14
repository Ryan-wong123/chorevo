package com.example.chorevo.leaderboard

enum class LeaderboardScope {
    GLOBAL,
    FRIENDS
}

enum class LeaderboardMetric(
    val label: String,
    val firestoreField: String
) {
    TOTAL_SESSIONS("Sessions", "totalPracticeSessions"),
    AVG_SIMILARITY("Similarity", "avgSimilarityScore"),
    TOTAL_ACTIVE_DAYS("Active Days", "streakDays");

    /** Handles score of. */
    fun scoreOf(stat: LeaderboardStat): Double {
        return when (this) {
            TOTAL_SESSIONS -> stat.totalPracticeSessions.toDouble()
            AVG_SIMILARITY -> stat.avgSimilarityScore
            TOTAL_ACTIVE_DAYS -> stat.totalActiveDays.toDouble()
        }
    }
}

data class LeaderboardStatsSnapshot(
    val displayName: String = "User",
    val photoUrl: String? = null,
    val totalPracticeSessions: Int = 0,
    val avgSimilarityScore: Double = 0.0,
    val totalActiveDays: Int = 0
)

data class LeaderboardStat(
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val totalPracticeSessions: Int = 0,
    val avgSimilarityScore: Double = 0.0,
    val totalActiveDays: Int = 0
)

data class LeaderboardEntry(
    val rank: Int,
    val uid: String,
    val displayName: String,
    val photoUrl: String?,
    val totalPracticeSessions: Int,
    val avgSimilarityScore: Double,
    val totalActiveDays: Int,
    val isMe: Boolean
)

data class LeaderboardUiState(
    val scope: LeaderboardScope = LeaderboardScope.GLOBAL,
    val metric: LeaderboardMetric = LeaderboardMetric.TOTAL_SESSIONS,
    val entries: List<LeaderboardEntry> = emptyList(),
    val myEntry: LeaderboardEntry? = null,
    val friendIds: Set<String> = emptySet(),
    val addingFriendUid: String? = null,
    val removingFriendUid: String? = null,
    val isLoading: Boolean = true
)
