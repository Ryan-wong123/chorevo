package com.example.chorevo.firebase.viewmodel

import androidx.lifecycle.viewmodel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.repository.PointsRepository
import com.example.chorevo.danceactivity.repository.PointsSnapshot
import com.example.chorevo.danceactivity.repository.WeeklyStreak
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
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
    private val pointsRepository: PointsRepository,
    private val danceLibraryRepository: DanceLibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** Refreshes streak, points, and liked dances for [uid]. */
    fun refresh(uid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }

            val streak = runCatching {
                firebaseStreakRepository.getWeeklyStreak(uid)
            }.getOrElse { WeeklyStreak() }

            val points = runCatching {
                pointsRepository.getPoints(uid)
            }.getOrElse { PointsSnapshot() }

            _uiState.update {
                it.copy(streak = streak, points = points, isLoading = false)
            }
        }

        loadLikedDances(uid)
    }

    /** Loads (or reloads) the list of dances this user has liked. */
    fun loadLikedDances(uid: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLikes = true) }
            val result = danceLibraryRepository.getLikedDances(uid)
            _uiState.update { state ->
                state.copy(
                    likedDances = result.getOrElse { emptyList() },
                    isLoadingLikes = false
                )
            }
        }
    }
}