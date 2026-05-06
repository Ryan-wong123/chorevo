package com.example.chorevo.dancelibrarylist.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.posedetection.PoseTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DanceLibraryUiState(
    val items: List<DanceLibraryModel> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

data class DanceSessionUploadUiState(
    val isWorking: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class DanceLibraryListViewModel @Inject constructor(
    private val repo: DanceLibraryRepository,
    private val streakRepository: FirebaseStreakRepository,
    private val leaderboardRepository: LeaderboardRepository
) : ViewModel() {
    private val templateCache = linkedMapOf<String, PoseTemplate>()


    private val _uiState = MutableStateFlow(DanceLibraryUiState(isLoading = true, message = "Loading dances…"))
    val uiState: StateFlow<DanceLibraryUiState> = _uiState.asStateFlow()

    private val _uploadState = MutableStateFlow(DanceSessionUploadUiState())
    val uploadState: StateFlow<DanceSessionUploadUiState> = _uploadState.asStateFlow()
    /** Refreshes when ready. */
    fun refreshWhenReady(isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            _uiState.update { it.copy(isLoading = true, message = "Signing in…") }
            return
        }
        refresh()
    }

    /** Handles refresh. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "Loading dances…") }

            val res = repo.listEnabled()
            res.fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            items = list,
                            isLoading = false,
                            message = if (list.isEmpty()) "No dances available." else null
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, message = "Failed: ${e.message}") }
                }
            )
        }
    }

    /** Saves session and upload. */
    fun saveSessionAndUpload(
        uid: String,
        session: DanceSessionEntity,
        extraAnalytics: Map<String, Any?> = emptyMap(),
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uploadState.update { it.copy(isWorking = true, message = null) }

            val res = repo.saveSessionAndUpload(
                session = session,
                extraAnalytics = extraAnalytics,
                uid = uid
            )

            res.fold(
                onSuccess = {
                    runCatching {
                        val updatedStreak = streakRepository.tickToday(uid)
                        leaderboardRepository.refreshUserStats(
                            uid = uid,
                            totalActiveDays = updatedStreak.totalStreakActivities
                        )
                    }
                    _uploadState.update { it.copy(isWorking = false, message = "Submitted practice result") }
                    onSuccess?.invoke()
                },
                onFailure = { e ->
                    _uploadState.update {
                        it.copy(
                            isWorking = false,
                            message = "Practice result submission failed. Saved locally. Upload failed: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    /** Clears upload message. */
    fun clearUploadMessage() {
        _uploadState.update { it.copy(message = null) }
    }

    /** Resolves the reference template before opening practice mode. */
    suspend fun preparePracticeItem(item: DanceLibraryModel): Result<DanceLibraryModel> {
        item.template?.let { template ->
            templateCache[item.danceId] = template
            return Result.success(item)
        }

        templateCache[item.danceId]?.let { cached ->
            return Result.success(item.copy(template = cached))
        }

        return repo.loadTemplate(item).mapCatching { template ->
            templateCache[item.danceId] = template
            item.copy(template = template)
        }
    }
}
