package com.example.chorevo.core.routes


import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.firebase.AuthScreen
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.viewmodel.AuthViewModel

/** Renders the authentication flow and forwards successful sign-in events. */
@Composable
fun AuthRoute(
    onAuthed: (UserProfile) -> Unit,
    vm: AuthViewModel = hiltViewModel()
) {
    AuthScreen(
        onAuthed = onAuthed,
        vm = vm
    )
}
