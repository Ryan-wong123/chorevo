package com.example.chorevo.weeklychallenge.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.weeklychallenge.Repository.WeeklyChallengeRepository
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
     * Folds a freshly completed practice session into this week's challenges.
     * Called alongside [com.example.chorevo.danceactivity.ViewModel.AchievementViewModel.processCompletedSession]
     * right after a session is submitted.
     */
    fun recordCompletedSession(
        uid: String,
        danceType: String,
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ) {
        viewModelScope.launch {
            runCatching {
                repository.recordCompletedSession(
                    uid = uid,
                    danceType = danceType,
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        challengeSet = result.challengeSet,
                        newlyCompleted = result.newlyCompleted,
                        bonusNewlyUnlocked = result.bonusNewlyUnlocked,
                        showCelebrationDialog = result.newlyCompleted.isNotEmpty() || result.bonusNewlyUnlocked,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Failed to update weekly challenges")
                }
            }
        }
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
