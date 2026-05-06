package com.example.chorevo.firebase.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.chorevo.firebase.AuthMode
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthEvent {
    data class Authed(val profile: UserProfile) : AuthEvent
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val status: String? = null,
    val loading: Boolean = false,
    val passwordVisible: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: FirebaseAuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Optional: LiveData version for rubrics / older patterns
    val uiStateLiveData = uiState.asLiveData()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /** Sets the active authentication mode and clears any previous status. */
    fun setMode(mode: AuthMode) {
        _uiState.update { it.copy(mode = mode, status = null) }
    }

    /** Toggles between login and sign-up modes. */
    fun toggleMode() {
        _uiState.update {
            val next = if (it.mode == AuthMode.LOGIN) AuthMode.SIGNUP else AuthMode.LOGIN
            it.copy(mode = next, status = null)
        }
    }

    /** Updates the email field. */
    fun setEmail(v: String) = _uiState.update { it.copy(email = v) }
    /** Updates the password field. */
    fun setPassword(v: String) = _uiState.update { it.copy(password = v) }
    /** Updates the display name field. */
    fun setDisplayName(v: String) = _uiState.update { it.copy(displayName = v) }
    /** Toggles whether the password field is currently visible. */
    fun togglePasswordVisible() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    /** Submits the current login or sign-up form. */
    fun submit() {
        val s = _uiState.value
        if (s.loading) return

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, status = null) }
            try {
                if (s.mode == AuthMode.SIGNUP) {
                    if (s.displayName.isBlank()) {
                        _uiState.update { it.copy(status = "Please enter a display name.") }
                        return@launch
                    }

                    val res =repo.signUp(
                        email = s.email.trim(),
                        password = s.password,
                        displayName = s.displayName.trim()
                    )

                    if (res.isSuccess) {
                        _events.tryEmit(AuthEvent.Authed(res.getOrThrow()))
                    } else {
                        _uiState.update { it.copy(status = res.exceptionOrNull()?.message) }
                    }
                } else {
                    val res =repo.signIn(s.email.trim(), s.password)
                    if (res.isSuccess) {
                        _events.tryEmit(AuthEvent.Authed(res.getOrThrow()))
                    } else {
                        _uiState.update { it.copy(status = res.exceptionOrNull()?.message) }
                    }
                }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }
}
