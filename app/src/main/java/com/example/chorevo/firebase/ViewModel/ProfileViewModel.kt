package com.example.chorevo.firebase.ViewModel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.Repository.PointsRepository
import com.example.chorevo.danceactivity.Repository.PointsSnapshot
import com.example.chorevo.danceactivity.Repository.WeeklyStreak
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val firebaseStreakRepository: FirebaseStreakRepository,
    private val pointsRepository: PointsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** Handles refresh. */
    fun refresh(uid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }

            val streak = runCatching {
                firebaseStreakRepository.getWeeklyStreak(uid)
            }.getOrElse {
                WeeklyStreak()
            }

            val points = runCatching {
                pointsRepository.getPoints(uid)
            }.getOrElse {
                PointsSnapshot()
            }

            _uiState.update {
                it.copy(
                    streak = streak,
                    points = points,
                    isLoading = false
                )
            }
        }
    }
}
