package com.routes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.example.chorevo.core.AppBottomNavBar
import com.example.chorevo.core.BottomNavItem
import com.example.chorevo.core.FloatingBarBottomMargin
import com.example.chorevo.core.FloatingBarHeight
import com.example.chorevo.core.FloatingBarState
import com.example.chorevo.core.LocalFloatingBarInset
import com.example.chorevo.core.LocalFloatingBarState
import com.example.chorevo.core.ViewModel.Screen

/**
 * Wraps app content with the floating bottom navigation bar.
 *
 * The bar floats over the content. Regular screens get extra bottom padding so
 * nothing sits under it; the home video feed is drawn full-bleed behind the bar
 * and reads [LocalFloatingBarInset] to keep its controls above it.
 */
@Composable
fun AppNavigationRoute(
    screen: Screen,
    onHomeSelected: () -> Unit,
    onUploadSelected: () -> Unit,
    onCommunitySelected: () -> Unit,
    onLeaderboardSelected: () -> Unit,
    onProfileSelected: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val showBar = screen.shouldShowBottomBar()
    val barState = remember { FloatingBarState() }
    val layoutDirection = LocalLayoutDirection.current

    Scaffold { scaffoldPadding ->
        // Room the floating bar takes above the system navigation bar.
        val barReserve = if (showBar) FloatingBarHeight + FloatingBarBottomMargin + 8.dp else 0.dp
        val fullBleed = screen == Screen.HOME

        val contentPadding = PaddingValues(
            start = scaffoldPadding.calculateStartPadding(layoutDirection),
            top = scaffoldPadding.calculateTopPadding(),
            end = scaffoldPadding.calculateEndPadding(layoutDirection),
            bottom = if (fullBleed) 0.dp else scaffoldPadding.calculateBottomPadding() + barReserve
        )
        val fullBleedInset = if (fullBleed) scaffoldPadding.calculateBottomPadding() + barReserve else 0.dp

        CompositionLocalProvider(
            LocalFloatingBarState provides barState,
            LocalFloatingBarInset provides fullBleedInset
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content(contentPadding)

                AnimatedVisibility(
                    visible = showBar && !barState.hidden,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = FloatingBarBottomMargin)
                ) {
                    AppBottomNavBar(
                        selected = screen.toBottomNavItem(),
                        onSelect = { tab ->
                            when (tab) {
                                BottomNavItem.HOME -> onHomeSelected()
                                BottomNavItem.UPLOAD -> onUploadSelected()
                                BottomNavItem.COMMUNITY -> onCommunitySelected()
                                BottomNavItem.LEADERBOARD -> onLeaderboardSelected()
                                BottomNavItem.PROFILE -> onProfileSelected()
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Hides shared navigation on immersive screens that need the full viewport. */
private fun Screen.shouldShowBottomBar(): Boolean = this != Screen.AUTH && this != Screen.DANCE_PRACTICE

/** Maps each screen to the bottom navigation item that should appear selected. */
private fun Screen.toBottomNavItem(): BottomNavItem = when (this) {
    Screen.AUTH -> BottomNavItem.HOME
    Screen.HOME, Screen.DANCE_PRACTICE -> BottomNavItem.HOME
    Screen.UPLOAD -> BottomNavItem.UPLOAD
    Screen.COMMUNITY -> BottomNavItem.COMMUNITY
    Screen.LEADERBOARD -> BottomNavItem.LEADERBOARD
    Screen.PROFILE, Screen.SETTINGS, Screen.WEEKLY_CHALLENGES -> BottomNavItem.PROFILE
}
