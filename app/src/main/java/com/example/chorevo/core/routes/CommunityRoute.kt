package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.community.CommunityScreen
import com.example.chorevo.community.ViewModel.CommunityViewModel
import com.example.chorevo.firebase.UserProfile

@Composable
fun CommunityRoute(
    profile: UserProfile,
    vm: CommunityViewModel = hiltViewModel()
) {
    CommunityScreen(
        profile = profile,
        vm = vm
    )
}
