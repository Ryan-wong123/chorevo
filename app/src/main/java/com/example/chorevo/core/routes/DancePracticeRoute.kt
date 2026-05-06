package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.danceactivity.DanceARPracticeScreen
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel

/** Renders the dance practice screen for the selected routine. */
@Composable
fun DancePracticeRoute(
    item: DanceLibraryModel,
    uid: String,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    danceLibraryVm: DanceLibraryListViewModel,
    achievementVm: AchievementViewModel = hiltViewModel()
) {
    DanceARPracticeScreen(
        item = item,
        onBack = onBack,
        onNavigateToProfile = onNavigateToProfile,
        vm = danceLibraryVm,
        uid = uid,
        achievementVm = achievementVm
    )
}
