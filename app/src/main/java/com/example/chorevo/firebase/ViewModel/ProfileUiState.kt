package com.example.chorevo.firebase.ViewModel

import com.example.chorevo.danceactivity.Repository.PointsSnapshot
import com.example.chorevo.danceactivity.Repository.WeeklyStreak
import com.example.chorevo.dancelibrarylist.DanceLibraryModel

data class ProfileUiState(
    val streak: WeeklyStreak = WeeklyStreak(),
    val points: PointsSnapshot = PointsSnapshot(),
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val likedDances: List<DanceLibraryModel> = emptyList(),
    val isLoadingLikes: Boolean = false
)