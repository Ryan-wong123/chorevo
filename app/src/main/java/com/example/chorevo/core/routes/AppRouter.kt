package com.routes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import com.example.chorevo.firebase.OnboardingScreen
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.core.ViewModel.Screen
import com.example.chorevo.core.routes.AuthRoute
import com.example.chorevo.core.routes.CommunityRoute
import com.example.chorevo.core.routes.DancePracticeRoute
import com.example.chorevo.core.routes.HomeRoute
import com.example.chorevo.core.routes.LeaderboardRoute
import com.example.chorevo.core.routes.ProfileSectionRoute
import com.example.chorevo.core.routes.UploadRoute
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel

/**
 * Routes the user to the correct top-level screen based on app state and auth status.
 *
 * [appViewModel] is provided by [MainActivity] so the same instance that received
 * the deep-link intent is used here (avoids a second hiltViewModel() lookup that
 * would create a fresh, uninitialized instance in some edge cases).
 */
@Composable
fun AppRouter(
    appViewModel: AppViewModel = hiltViewModel()
) {
    val state = appViewModel.uiState.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }

    // Show deep-link errors (dance not found, disabled, etc.) as a snackbar.
    LaunchedEffect(state.deepLinkError) {
        val error = state.deepLinkError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        appViewModel.clearDeepLinkError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

        // Keep startup neutral until session restore finishes so logged-in users
        // do not briefly see the login UI.
        if (!state.didAuthCheck) {
            StartupLoadingRoute()
            return@Scaffold
        }

        // After auth check, if no signed-in profile exists, force auth
        // First launch shows the welcome carousel, then sign-in.
        if (state.profile == null) {
            Crossfade(targetState = state.onboardingSeen, label = "onboarding") { seen ->
                when (seen) {
                    null -> StartupLoadingRoute()
                    false -> OnboardingScreen(onFinish = appViewModel::completeOnboarding)
                    true -> AuthRoute(onAuthed = appViewModel::onAuthed)
                }
            }
            return@Scaffold
        }

        val danceLibraryViewModel: DanceLibraryListViewModel = hiltViewModel()
        val videoUploadsViewModel: VideoUploadsViewModel = hiltViewModel()

        AppNavigationRoute(
            screen = state.screen,
            onHomeSelected = appViewModel::backToHome,
            onUploadSelected = appViewModel::openUpload,
            onCommunitySelected = appViewModel::openCommunity,
            onLeaderboardSelected = appViewModel::openLeaderboard,
            onProfileSelected = appViewModel::openProfile
        ) { navInnerPadding ->
            AppRouteContent(
                innerPadding = navInnerPadding,
                screen = state.screen,
                uid = state.profile.uid,
                onAuthed = appViewModel::onAuthed,
                onPickDance = appViewModel::pickDance,
                onBackToHome = appViewModel::backToHome,
                onOpenProfile = appViewModel::openProfile,
                selectedDanceItem = state.selectedDanceItem,
                sharedDance = state.sharedDance,
                profile = state.profile,
                appViewModel = appViewModel,
                danceLibraryViewModel = danceLibraryViewModel,
                videoUploadsViewModel = videoUploadsViewModel
            )
        }
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
    sharedDance: DanceLibraryModel?,
    profile: UserProfile,
    appViewModel: AppViewModel,
    danceLibraryViewModel: DanceLibraryListViewModel,
    videoUploadsViewModel: VideoUploadsViewModel
) {
    // Predictive back: while the user drags the back gesture the current screen
    // shrinks and rounds its corners; releasing goes to Screen.backTarget.
    var backProgress by remember { mutableFloatStateOf(0f) }
    val backScale by animateFloatAsState(1f - 0.1f * backProgress, label = "backScale")
    LaunchedEffect(screen) { backProgress = 0f }
    PredictiveBackHandler(enabled = screen.backTarget != null) { events ->
        try {
            events.collect { backProgress = it.progress }
            appViewModel.navigateBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    Box(
        modifier = Modifier
            .padding(innerPadding)
            .graphicsLayer {
                scaleX = backScale
                scaleY = backScale
                shape = RoundedCornerShape((backProgress * 32).dp)
                clip = backProgress > 0f
            }
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { screenTransition(initialState, targetState) },
            label = "screen"
        ) { screen ->
            when (screen) {
                Screen.AUTH -> {
                    AuthRoute(
                        onAuthed = onAuthed
                    )
                }

                Screen.HOME -> {
                    HomeRoute(
                        uid = uid,
                        username = profile.displayName ?: "User",
                        onPickDance = onPickDance,
                        onBack = {},
                        vm = danceLibraryViewModel,
                        sharedDance = sharedDance
                    )
                }

                Screen.DANCE_PRACTICE -> {
                    // Leaving practice clears the selection before the exit animation ends;
                    // keep showing the last dance while this content slides away.
                    val practiceItem = rememberLastNonNull(selectedDanceItem)
                    if (practiceItem == null) {
                        LaunchedEffect(Unit) { onBackToHome() }
                    } else {
                        DancePracticeRoute(
                            item = practiceItem,
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

                Screen.COMMUNITY -> {
                    CommunityRoute(
                        profile = profile,
                        onOpenDance = appViewModel::openDanceById,
                        pendingInviteCode = appViewModel.uiState.collectAsStateWithLifecycle().value.pendingGroupInvite,
                        onInviteHandled = appViewModel::clearGroupInvite
                    )
                }

                Screen.LEADERBOARD -> {
                    LeaderboardRoute(
                        myUid = uid
                    )
                }

                Screen.PROFILE, Screen.SETTINGS, Screen.WEEKLY_CHALLENGES -> {
                    ProfileSectionRoute(
                        screen = screen,
                        profile = profile,
                        appViewModel = appViewModel,
                        videoUploadsVm = videoUploadsViewModel,
                        danceLibraryVm = danceLibraryViewModel
                    )
                }
            }
        }
    }
}

/**
 * Practice slides up over the feed like a sheet and back down when closed;
 * everything else uses a quick fade-through with a slight scale.
 */
private fun screenTransition(from: Screen, to: Screen): ContentTransform = when {
    to == Screen.DANCE_PRACTICE ->
        (slideInVertically(tween(320)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
    from == Screen.DANCE_PRACTICE ->
        fadeIn(tween(220)) togetherWith (slideOutVertically(tween(280)) { it / 4 } + fadeOut(tween(200)))
    else ->
        (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.96f)) togetherWith
            fadeOut(tween(90))
}

/** Returns [value], or the last non-null value seen while it is null. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
