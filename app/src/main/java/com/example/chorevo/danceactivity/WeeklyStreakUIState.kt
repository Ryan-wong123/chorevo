package com.example.chorevo.danceactivity

data class WeeklyStreakUiState(
    val isLoading: Boolean = false,
    val weekStart: String = "",
    val daysMask: Int = 0,
    val totalStreakActivities: Int = 0,
    val lastActivityDate: String? = null,
    val errorMessage: String? = null
) {
    /** Checks whether day completed. */
    fun isDayCompleted(dayIndexMon0: Int): Boolean {
        return ((daysMask shr dayIndexMon0) and 1) == 1
    }
}
