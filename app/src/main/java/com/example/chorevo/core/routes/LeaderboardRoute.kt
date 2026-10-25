package com.example.chorevo.core.routes


import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.leaderboard.LeaderboardScreen
import com.example.chorevo.leaderboard.viewmodel.LeaderboardViewModel

/** Renders the leaderboard screen with ranking data for the current user. */
@Composable
fun LeaderboardRoute(
    myUid: String,
    vm: LeaderboardViewModel = hiltViewModel()
) {
    LeaderboardScreen(
        myUid = myUid,
        vm = vm
    )
}
