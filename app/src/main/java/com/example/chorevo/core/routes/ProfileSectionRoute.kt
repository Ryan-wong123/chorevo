package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.core.ViewModel.Screen
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.UserProfileScreen
import com.example.chorevo.firebase.ViewModel.ProfileViewModel
import com.example.chorevo.notification.SettingsScreen
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
import com.example.chorevo.weeklychallenge.WeeklyChallengesScreen
import com.example.chorevo.weeklychallenge.ViewModel.WeeklyChallengeViewModel

/** Renders the profile area and switches between profile, settings, and weekly-challenges content. */
@Composable
fun ProfileSectionRoute(
    screen: Screen,
    profile: UserProfile,
    appViewModel: AppViewModel,
    videoUploadsVm: VideoUploadsViewModel,
    danceLibraryVm: DanceLibraryListViewModel,
    profileVm: ProfileViewModel = hiltViewModel(),
    analyticsVm: AnalyticsDashboardViewModel = hiltViewModel(),
    weeklyChallengeVm: WeeklyChallengeViewModel = hiltViewModel()
) {
    when (screen) {
        Screen.PROFILE -> {
            UserProfileScreen(
                profile = profile,
                onLogout = appViewModel::logout,
                onOpenSettings = appViewModel::openSettings,
                onOpenWeeklyChallenges = appViewModel::openWeeklyChallenges,
                analyticsVm = analyticsVm,
                videoUploadsVm = videoUploadsVm,
                profileVm = profileVm,
                danceLibraryVm = danceLibraryVm,
                weeklyChallengeVm = weeklyChallengeVm
            )
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                appViewModel = appViewModel,
                profile = profile,
                onBack = appViewModel::openProfile,
                onLogout = appViewModel::logout
            )
        }

        Screen.WEEKLY_CHALLENGES -> {
            WeeklyChallengesScreen(
                uid = profile.uid,
                vm = weeklyChallengeVm,
                onBack = appViewModel::openProfile
            )
        }

        else -> Unit
    }
}