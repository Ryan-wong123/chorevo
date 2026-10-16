package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.community.GroupsScreen
import com.example.chorevo.community.ViewModel.GroupsViewModel
import com.example.chorevo.firebase.UserProfile

@Composable
fun CommunityRoute(
    profile: UserProfile,
    onOpenDance: (String) -> Unit,
    vm: GroupsViewModel = hiltViewModel()
) {
    GroupsScreen(profile = profile, vm = vm, onOpenDance = onOpenDance)
}
