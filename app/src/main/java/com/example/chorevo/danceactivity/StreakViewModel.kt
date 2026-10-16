package com.example.chorevo.danceactivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreakViewModel(
    private val repository: FirebaseStreakRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyStreakUiState(isLoading = true))
    val uiState: StateFlow<WeeklyStreakUiState> = _uiState.asStateFlow()

    /** Loads weekly streak. */
    fun loadWeeklyStreak(uid: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            try {
                val streak = repository.getWeeklyStreak(uid)

                _uiState.value = WeeklyStreakUiState(
                    isLoading = false,
                    weekStart = streak.weekStart,
                    daysMask = streak.daysMask,
                    totalStreakActivities = streak.totalStreakActivities,
                    lastActivityDate = streak.lastActivityDate,
                    errorMessage = null
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Failed to load streak"
                )
            }
        }
    }

    /** Clears error. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
