package com.routes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import com.example.chorevo.core.AppBottomNavBar
import com.example.chorevo.core.BottomNavItem
import com.example.chorevo.core.ViewModel.Screen

/** Wraps app content with the shared bottom navigation bar for the main sections. */
@Composable
fun AppNavigationRoute(
    screen: Screen,
    onHomeSelected: () -> Unit,
    onUploadSelected: () -> Unit,
    onLeaderboardSelected: () -> Unit,
    onProfileSelected: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        bottomBar = {
            if (screen.shouldShowBottomBar()) {
                AppBottomNavBar(
                    selected = screen.toBottomNavItem(),
                    onSelect = { tab ->
                        when (tab) {
                            BottomNavItem.HOME -> onHomeSelected()
                            BottomNavItem.UPLOAD -> onUploadSelected()
                            BottomNavItem.LEADERBOARD -> onLeaderboardSelected()
                            BottomNavItem.PROFILE -> onProfileSelected()
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        content(innerPadding)
    }
}

/** Hides the bottom bar only on the authentication screen. */
private fun Screen.shouldShowBottomBar(): Boolean = this != Screen.AUTH

/** Maps each screen to the bottom navigation item that should appear selected. */
private fun Screen.toBottomNavItem(): BottomNavItem = when (this) {
    Screen.AUTH -> BottomNavItem.HOME
    Screen.HOME, Screen.DANCE_PRACTICE -> BottomNavItem.HOME
    Screen.UPLOAD -> BottomNavItem.UPLOAD
    Screen.LEADERBOARD -> BottomNavItem.LEADERBOARD
    Screen.PROFILE, Screen.SETTINGS -> BottomNavItem.PROFILE
}
