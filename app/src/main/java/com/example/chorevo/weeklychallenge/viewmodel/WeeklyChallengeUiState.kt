package com.example.chorevo.weeklychallenge.viewmodel

import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet

data class WeeklyChallengeUiState(
    val loading: Boolean = false,
    val challengeSet: WeeklyChallengeSet = WeeklyChallengeSet(),

    val newlyCompleted: List<WeeklyChallenge> = emptyList(),
    val bonusNewlyUnlocked: Boolean = false,
    val showCelebrationDialog: Boolean = false,

    val errorMessage: String? = null
) {
    val challenges: List<WeeklyChallenge>
        get() = challengeSet.challenges

    val completedCount: Int
        get() = challenges.count { it.completed }
}
