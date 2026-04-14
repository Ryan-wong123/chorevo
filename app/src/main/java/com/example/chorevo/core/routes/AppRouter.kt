package com.routes



import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.core.ViewModel.Screen
import com.example.chorevo.core.routes.AuthRoute
import com.example.chorevo.core.routes.DancePracticeRoute
import com.example.chorevo.core.routes.HomeRoute
import com.example.chorevo.core.routes.LeaderboardRoute
import com.example.chorevo.core.routes.ProfileSectionRoute
import com.example.chorevo.core.routes.UploadRoute
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel

/** Routes the user to the correct top-level screen based on app state and auth status. */
@Composable
fun AppRouter(
    appViewModel: AppViewModel = hiltViewModel()
) {
    val state = appViewModel.uiState.collectAsStateWithLifecycle().value

    // Keep startup neutral until session restore finishes so logged-in users
    // do not briefly see the login UI.
    if (!state.didAuthCheck) {
        StartupLoadingRoute()
        return
    }

    // After auth check, if no signed-in profile exists, force auth
    if (state.profile == null) {
        AuthRoute(
            onAuthed = appViewModel::onAuthed
        )
        return
    }

    val danceLibraryViewModel: DanceLibraryListViewModel = hiltViewModel()
    val videoUploadsViewModel: VideoUploadsViewModel = hiltViewModel()

    AppNavigationRoute(
        screen = state.screen,
        onHomeSelected = appViewModel::backToHome,
        onUploadSelected = appViewModel::openUpload,
        onLeaderboardSelected = appViewModel::openLeaderboard,
        onProfileSelected = appViewModel::openProfile
    ) { innerPadding ->
        AppRouteContent(
            innerPadding = innerPadding,
            screen = state.screen,
            uid = state.profile.uid,
            onAuthed = appViewModel::onAuthed,
            onPickDance = appViewModel::pickDance,
            onBackToHome = appViewModel::backToHome,
            onOpenProfile = appViewModel::openProfile,
            selectedDanceItem = state.selectedDanceItem,
            profile = state.profile,
            appViewModel = appViewModel,
            danceLibraryViewModel = danceLibraryViewModel,
            videoUploadsViewModel = videoUploadsViewModel
        )
    }
}

@Composable
private fun StartupLoadingRoute() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** Renders the active screen content inside the shared navigation scaffold. */
@Composable
private fun AppRouteContent(
    innerPadding: PaddingValues,
    screen: Screen,
    uid: String,
    onAuthed: (UserProfile) -> Unit,
    onPickDance: (DanceLibraryModel) -> Unit,
    onBackToHome: () -> Unit,
    onOpenProfile: () -> Unit,
    selectedDanceItem: DanceLibraryModel?,
    profile: UserProfile,
    appViewModel: AppViewModel,
    danceLibraryViewModel: DanceLibraryListViewModel,
    videoUploadsViewModel: VideoUploadsViewModel
) {
    Box(modifier = Modifier.padding(innerPadding)) {
        when (screen) {
            Screen.AUTH -> {
                AuthRoute(
                    onAuthed = onAuthed
                )
            }

            Screen.HOME -> {
                HomeRoute(
                    uid = uid,
                    onPickDance = onPickDance,
                    onBack = {},
                    vm = danceLibraryViewModel
                )
            }

            Screen.DANCE_PRACTICE -> {
                if (selectedDanceItem == null) {
                    onBackToHome()
                } else {
                    DancePracticeRoute(
                        item = selectedDanceItem,
                        uid = uid,
                        onBack = onBackToHome,
                        onNavigateToProfile = onOpenProfile,
                        danceLibraryVm = danceLibraryViewModel
                    )
                }
            }

            Screen.UPLOAD -> {
                UploadRoute(
                    onBack = onBackToHome,
                    vm = videoUploadsViewModel
                )
            }

            Screen.LEADERBOARD -> {
                LeaderboardRoute(
                    myUid = uid
                )
            }

            Screen.PROFILE, Screen.SETTINGS -> {
                ProfileSectionRoute(
                    screen = screen,
                    profile = profile,
                    appViewModel = appViewModel,
                    videoUploadsVm = videoUploadsViewModel
                )
            }
        }
    }
}
