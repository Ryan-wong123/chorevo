package com.example.chorevo.danceactivity.viewmodel


import androidx.lifecycle.viewmodel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.ALL_BADGES
import com.example.chorevo.danceactivity.repository.AchievementRepository
import com.example.chorevo.danceactivity.repository.SessionRewardsRepository
import com.example.chorevo.danceactivity.SessionRewards
import com.example.chorevo.danceactivity.levelForPoints
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val repository: AchievementRepository,
    private val rewardsRepository: SessionRewardsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AchievementUiState())
    val uiState: StateFlow<AchievementUiState> = _uiState.asStateFlow()

    /** Loads achievements. */
    fun loadAchievements(uid: String, totalPoints: Int = 0) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    errorMessage = null
                )
            }

            runCatching {
                val earnedIds = repository.getEarnedBadges(uid)
                val earnedBadges = ALL_BADGES.filter { badge -> badge.id in earnedIds }

                _uiState.update {
                    it.copy(
                        loading = false,
                        earnedBadgeIds = earnedIds,
                        earnedBadges = earnedBadges,
                        totalPoints = totalPoints,
                        currentLevel = levelForPoints(totalPoints),
                        previousLevel = levelForPoints(totalPoints),
                        leveledUp = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.message ?: "Failed to load achievements"
                    )
                }
            }
        }
    }

    private var rewardsJob: Job? = null

    /**
     * Waits for the server to reward [sessionId] (rewardsFromSession) and shows
     * the result dialog with its points, badges and level. If the session
     * hasn't synced within [REWARDS_TIMEOUT_MS] (for example offline), the
     * dialog shows the scores and says the points will follow. [onRewards]
     * gets the rewards (for the weekly challenge celebration).
     */
    fun awaitSessionRewards(uid: String, sessionId: String, onRewards: (SessionRewards) -> Unit = {}) {
        rewardsJob?.cancel()
        rewardsJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }

            val rewards = runCatching {
                withTimeoutOrNull(REWARDS_TIMEOUT_MS) {
                    rewardsRepository.observe(uid, sessionId).filterNotNull().firstOrNull()
                }
            }.getOrNull()
            if (rewards == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        newlyUnlocked = emptyList(),
                        sessionPoints = 0,
                        leveledUp = false,
                        rewardsPending = true,
                        rewardsCapped = false,
                        showAchievementDialog = true
                    )
                }
                return@launch
            }

            val earnedIds = runCatching { repository.getEarnedBadges(uid) }.getOrDefault(emptySet())
            val prevLevel = levelForPoints(rewards.totalPointsBefore)
            val newLevel = levelForPoints(rewards.totalPointsAfter)
            _uiState.update {
                it.copy(
                    loading = false,
                    earnedBadgeIds = earnedIds,
                    earnedBadges = ALL_BADGES.filter { badge -> badge.id in earnedIds },
                    newlyUnlocked = ALL_BADGES.filter { badge -> badge.id in rewards.newBadges },
                    sessionPoints = rewards.sessionPoints,
                    totalPoints = rewards.totalPointsAfter,
                    previousLevel = prevLevel,
                    currentLevel = newLevel,
                    leveledUp = newLevel.level > prevLevel.level,
                    rewardsPending = false,
                    rewardsCapped = rewards.capped,
                    showAchievementDialog = true,
                    errorMessage = null
                )
            }
            onRewards(rewards)
        }
    }

    /** Handles dismiss achievement dialog. */
    fun dismissAchievementDialog() {
        _uiState.update {
            it.copy(
                showAchievementDialog = false,
                rewardsPending = false,
                rewardsCapped = false,
                newlyUnlocked = emptyList(),
                sessionPoints = 0,
                leveledUp = false
            )
        }
    }

    /** Clears error. */
    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null)
        }
    }

    private companion object {
        const val REWARDS_TIMEOUT_MS = 20_000L
    }
}
