package com.example.chorevo.firebase.viewmodel

import androidx.lifecycle.viewmodel
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.chorevo.firebase.AuthMode
import com.example.chorevo.firebase.AuthProvider
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
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
    data object LaunchGoogleSignIn : AuthEvent
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val status: String? = null,
    val loading: Boolean = false,
    val passwordVisible: Boolean = false,
    val activeProvider: AuthProvider? = null
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

        if (s.mode == AuthMode.SIGNUP && s.displayName.isBlank()) {
            _uiState.update { it.copy(status = "Please enter a display name.") }
            return
        }
        if (s.mode == AuthMode.SIGNUP && s.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update { it.copy(status = "Use a password with at least $MIN_PASSWORD_LENGTH characters.") }
            return
        }

        submitAuth(AuthProvider.EMAIL) {
            if (s.mode == AuthMode.SIGNUP) {
                repo.signUp(
                    email = s.email.trim(),
                    password = s.password,
                    displayName = s.displayName.trim()
                )
            } else {
                repo.signIn(s.email.trim(), s.password)
            }
        }
    }

    /**
     * Emails a password reset link. The message is the same whether or not
     * the email has an account, so nobody can probe which emails are registered.
     */
    fun sendPasswordReset() {
        val email = _uiState.value.email.trim()
        if (email.isBlank() || !email.contains('@')) {
            _uiState.update { it.copy(status = "Enter your email above, then tap \"Forgot password?\".") }
            return
        }
        viewModelScope.launch {
            repo.sendPasswordReset(email)
            _uiState.update { it.copy(status = "If an account exists for $email, we've emailed a link to reset the password.") }
        }
    }

    /** Starts the Google sign-in flow. */
    fun requestGoogleSignIn() {
        if (_uiState.value.loading) return
        _uiState.update {
            it.copy(
                loading = true,
                status = null,
                activeProvider = AuthProvider.GOOGLE
            )
        }
        _events.tryEmit(AuthEvent.LaunchGoogleSignIn)
    }

    /** Completes the Google sign-in flow after the UI fetches a Google ID token. */
    fun completeGoogleSignIn(idToken: String) {
        submitAuth(AuthProvider.GOOGLE) {
            repo.signInWithGoogle(idToken)
        }
    }

    /** Restores the auth screen after an external auth flow is cancelled or fails before Firebase runs. */
    fun onExternalAuthCancelled(message: String? = null) {
        _uiState.update {
            it.copy(
                loading = false,
                activeProvider = null,
                status = message
            )
        }
    }

    private fun submitAuth(
        provider: AuthProvider,
        block: suspend () -> Result<UserProfile>
    ) {
        if (_uiState.value.loading && provider != AuthProvider.GOOGLE) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    status = null,
                    activeProvider = provider
                )
            }
            try {
                val res = block()
                if (res.isSuccess) {
                    _events.tryEmit(AuthEvent.Authed(res.getOrThrow()))
                } else {
                    _uiState.update { state ->
                        state.copy(status = authErrorMessage(res.exceptionOrNull()))
                    }
                }
            } finally {
                _uiState.update {
                    it.copy(
                        loading = false,
                        activeProvider = null
                    )
                }
            }
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

/**
 * The message to show for a failed sign-in. Wrong email and wrong password
 * read the same, so the screen doesn't reveal which emails have accounts.
 */
internal fun authErrorMessage(error: Throwable?): String? = when (error) {
    is FirebaseAuthInvalidUserException,
    is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
    is FirebaseAuthUserCollisionException -> "Couldn't create an account with that email. Try signing in instead."
    is FirebaseAuthWeakPasswordException -> "Use a stronger password (at least 8 characters)."
    else -> error?.message
}
