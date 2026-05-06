package com.example.chorevo.danceactivity.ViewModel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.ALL_BADGES
import com.example.chorevo.danceactivity.Repository.AchievementRepository
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.Repository.PointsRepository
import com.example.chorevo.danceactivity.evaluateNewBadges
import com.example.chorevo.danceactivity.levelForPoints
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AchievementViewModel @Inject constructor(
    private val repository: AchievementRepository,
    private val streakRepository: FirebaseStreakRepository,
    private val pointsRepository: PointsRepository
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

    /** Handles process completed session. */
    fun processCompletedSession(
        uid: String,
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    errorMessage = null
                )
            }

            runCatching {
                // 1) Award points
                val earned = pointsRepository.calculateSessionPoints(
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore
                )
                val pointsSnap = pointsRepository.addSessionPoints(uid, earned)

                // 2) Existing earned badges
                val alreadyEarned = repository.getEarnedBadges(uid)

                // 3) Streak state
                val streakSnap = streakRepository.getWeeklyStreak(
                    uid = uid,
                    autoResetIfNewWeek = false
                )

                // 4) Evaluate new badges
                val isFirst = alreadyEarned.isEmpty()

                val newBadgeIds = evaluateNewBadges(
                    sessionPoints = earned,
                    totalPointsAfter = pointsSnap.totalPoints,
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore,
                    isFirstSession = isFirst,
                    weekDaysMask = streakSnap.daysMask,
                    previouslyEarned = alreadyEarned
                )

                val newBadgeDefs = repository.awardAndGetNew(uid, newBadgeIds)

                // 5) Refresh earned badges after write
                val updatedEarnedIds = repository.getEarnedBadges(uid)
                val updatedEarnedBadges = ALL_BADGES.filter { it.id in updatedEarnedIds }

                // 6) Level-up calculation
                val prevPoints = pointsSnap.totalPoints - earned
                val prevLevel = levelForPoints(prevPoints)
                val newLevel = levelForPoints(pointsSnap.totalPoints)
                val leveledUp = newLevel.level > prevLevel.level

                _uiState.update {
                    it.copy(
                        loading = false,
                        earnedBadgeIds = updatedEarnedIds,
                        earnedBadges = updatedEarnedBadges,
                        newlyUnlocked = newBadgeDefs,
                        sessionPoints = earned,
                        totalPoints = pointsSnap.totalPoints,
                        previousLevel = prevLevel,
                        currentLevel = newLevel,
                        leveledUp = leveledUp,
                        showAchievementDialog = true,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = error.message ?: "Failed to process achievements"
                    )
                }
            }
        }
    }

    /** Handles dismiss achievement dialog. */
    fun dismissAchievementDialog() {
        _uiState.update {
            it.copy(
                showAchievementDialog = false,
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
}
