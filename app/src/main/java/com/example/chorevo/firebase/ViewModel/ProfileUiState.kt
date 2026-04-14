package com.example.chorevo.firebase.ViewModel

import com.example.chorevo.danceactivity.Repository.PointsSnapshot
import com.example.chorevo.danceactivity.Repository.WeeklyStreak

data class ProfileUiState(
    val streak: WeeklyStreak = WeeklyStreak(),
    val points: PointsSnapshot = PointsSnapshot(),
    val isLoading: Boolean = false,
    val statusMessage: String? = null
)