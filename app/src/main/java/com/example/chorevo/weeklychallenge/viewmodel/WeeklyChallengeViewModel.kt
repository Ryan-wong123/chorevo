package com.example.chorevo.weeklychallenge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.SessionRewards
import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.repository.WeeklyChallengeRepository
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WeeklyChallengeViewModel @Inject constructor(
    private val repository: WeeklyChallengeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyChallengeUiState())
    val uiState: StateFlow<WeeklyChallengeUiState> = _uiState.asStateFlow()

    /** Loads (or lazily generates) this week's personalized challenge set for [uid]. */
    fun loadForWeek(uid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }

            runCatching { repository.getOrCreateCurrentWeek(uid) }
                .onSuccess { set ->
                    _uiState.update {
                        it.copy(loading = false, challengeSet = set, errorMessage = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            errorMessage = error.message ?: "Failed to load weekly challenges"
                        )
                    }
                }
        }
    }

    /**
     * Shows the celebration for weekly challenges a practice session just
     * completed (the server advanced them and awarded their points; see
     * AchievementViewModel.awaitSessionRewards), then reloads the week.
     */
    fun onSessionRewards(uid: String, rewards: SessionRewards) {
        val completed = rewards.completedChallenges.map { c ->
            WeeklyChallenge(
                id = c.id,
                type = challengeTypeFor(c.id),
                title = c.title,
                description = "",
                emoji = c.emoji,
                targetCount = 0,
                rewardPoints = c.rewardPoints,
                completed = true
            )
        }
        if (completed.isNotEmpty() || rewards.bonusUnlocked) {
            _uiState.update {
                it.copy(
                    newlyCompleted = completed,
                    bonusNewlyUnlocked = rewards.bonusUnlocked,
                    challengeSet = if (rewards.bonusUnlocked && rewards.bonusPoints > 0) {
                        it.challengeSet.copy(bonusRewardPoints = rewards.bonusPoints)
                    } else {
                        it.challengeSet
                    },
                    showCelebrationDialog = true
                )
            }
        }
        loadForWeek(uid)
    }

    private fun challengeTypeFor(id: String): ChallengeType = when (id) {
        "focus_drill" -> ChallengeType.FOCUS_DRILL
        "style_explorer" -> ChallengeType.STYLE_EXPLORER
        else -> ChallengeType.MOMENTUM_GOAL
    }

    /** Dismisses the completion celebration dialog after the user has seen it. */
    fun dismissCelebrationDialog() {
        _uiState.update {
            it.copy(
                showCelebrationDialog = false,
                newlyCompleted = emptyList(),
                bonusNewlyUnlocked = false
            )
        }
    }

    /** Clears the current error message after it has been shown. */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
