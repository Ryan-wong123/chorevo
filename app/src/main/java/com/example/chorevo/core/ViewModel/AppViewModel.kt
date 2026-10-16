package com.example.chorevo.core.ViewModel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.core.LocalUserFiles
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.firebase.AutoRetryUploaderService
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.notification.NotificationPreferencesDataStore
import com.example.chorevo.notification.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authRepository: FirebaseAuthRepository,
    private val retryUploader: AutoRetryUploaderService,
    private val settings: NotificationPreferencesDataStore,
    private val danceLibraryRepository: DanceLibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val reminderScheduler = ReminderScheduler(appContext, settings)

    /**
     * Holds a danceId that arrived via a deep link before auth finished.
     * Once auth resolves we consume this and navigate to the dance.
     */
    private var pendingDeepLinkDanceId: String? = null

    /** Group invite code from a link that arrived before sign-in. */
    private var pendingInviteCode: String? = null

    /** A group-invitation push was tapped before auth was ready. */
    private var pendingOpenGroups = false

    init {
        viewModelScope.launch {
            val p = authRepository.getCurrentUserProfile()
            _uiState.update {
                it.copy(
                    profile = p,
                    screen = if (p == null) Screen.AUTH else Screen.HOME,
                    didAuthCheck = true
                )
            }

            if (p != null) {
                retryUploader.retryUnsyncedSessions(p.uid)
                // Consume any deep link that arrived before auth was ready.
                pendingDeepLinkDanceId?.let { id ->
                    pendingDeepLinkDanceId = null
                    fetchAndOpenDance(id)
                }
                consumePendingInvite()
            }
        }

        viewModelScope.launch {
            settings.pushEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(pushEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settings.onboardingSeenFlow.collect { seen ->
                _uiState.update { it.copy(onboardingSeen = seen) }
            }
        }

        viewModelScope.launch {
            settings.themeModeFlow.collect { key ->
                _uiState.update { it.copy(themeMode = ThemeMode.fromKey(key)) }
            }
        }

        viewModelScope.launch {
            settings.notificationTimeFlow.collect { (hour, minute) ->
                _uiState.update {
                    it.copy(
                        notificationHour = hour,
                        notificationMinute = minute
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deep-link entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by [MainActivity] when a dance share link is tapped. If auth has
     * already resolved we navigate immediately; otherwise we park the ID and
     * send the user to the auth screen so that once they log in, onAuthed()
     * will consume it.
     */
    fun openDanceById(danceId: String) {
        Log.d("DeepLink", "openDanceById called with id=$danceId")
        val state = _uiState.value
        if (!state.didAuthCheck || state.profile == null) {
            Log.d("DeepLink", "Auth not ready — parking danceId and showing AUTH screen")
            pendingDeepLinkDanceId = danceId
            _uiState.update { it.copy(screen = Screen.AUTH) }
            return
        }
        Log.d("DeepLink", "Auth ready — fetching dance immediately")
        viewModelScope.launch { fetchAndOpenDance(danceId) }
    }

    /**
     * Fetches the [DanceLibraryModel] for [danceId] from Firestore and shows its
     * video at the top of the home feed, or sets a user-visible error if the
     * fetch fails. Practising stays an explicit choice via "Start Practice".
     */
    private suspend fun fetchAndOpenDance(danceId: String) {
        Log.d("DeepLink", "fetchAndOpenDance started for id=$danceId")
        _uiState.update { it.copy(deepLinkLoading = true, deepLinkError = null) }

        val result = danceLibraryRepository.getDanceById(danceId, _uiState.value.profile?.uid)

        result.fold(
            onSuccess = { dance ->
                Log.d("DeepLink", "Dance fetched: ${dance.danceId}")
                _uiState.update {
                    it.copy(
                        sharedDance = dance,
                        selectedDanceItem = null,
                        screen = Screen.HOME,
                        deepLinkLoading = false,
                        deepLinkError = null
                    )
                }
            },
            onFailure = { error ->
                Log.e("DeepLink", "Failed to fetch dance: ${error.message}", error)
                _uiState.update {
                    it.copy(
                        deepLinkLoading = false,
                        deepLinkError = error.message ?: "Could not open shared dance."
                    )
                }
            }
        )
    }

    /** Clears any deep-link error banner after it has been shown. */
    fun clearDeepLinkError() {
        _uiState.update { it.copy(deepLinkError = null) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Existing functions (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    /** Updates whether daily reminder notifications are enabled and reschedules them if needed. */
    fun setPushEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setPushEnabled(enabled)
            reminderScheduler.rescheduleFromPreferences()
        }
    }

    /** Saves the preferred reminder time and refreshes the scheduled notification when enabled. */
    fun setNotificationTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settings.setNotificationTime(hour, minute)
            if (_uiState.value.pushEnabled) {
                reminderScheduler.rescheduleFromPreferences()
            }
        }
    }

    /** Uploads a new profile photo and updates the current user profile state with the result. */
    fun uploadProfilePhoto(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUploadingProfilePhoto = true,
                    profilePhotoUploadError = null
                )
            }

            val result = authRepository.uploadProfilePhoto(imageUri)

            _uiState.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        profile = result.getOrThrow(),
                        isUploadingProfilePhoto = false,
                        profilePhotoUploadError = null
                    )
                } else {
                    current.copy(
                        isUploadingProfilePhoto = false,
                        profilePhotoUploadError = result.exceptionOrNull()?.message
                            ?: "Failed to upload profile photo"
                    )
                }
            }
        }
    }

    /** Clears the current profile photo upload error after it has been shown to the user. */
    fun clearProfilePhotoUploadError() {
        _uiState.update { it.copy(profilePhotoUploadError = null) }
    }

    /** Stores the authenticated user and sends the app to the home screen. */
    fun onAuthed(profile: UserProfile) {
        _uiState.update { it.copy(profile = profile, screen = Screen.HOME) }
        // Upload any sessions this account saved offline before signing out.
        viewModelScope.launch { runCatching { retryUploader.retryUnsyncedSessions(profile.uid) } }
        // Consume any deep link that arrived before the user logged in.
        pendingDeepLinkDanceId?.let { id ->
            pendingDeepLinkDanceId = null
            viewModelScope.launch { fetchAndOpenDance(id) }
        }
        consumePendingInvite()
    }

    /**
     * Called by [MainActivity] when a group invite link (or its QR code, scanned
     * with the phone camera) is opened: shows Groups with the join prompt. While
     * signed out the code is kept until sign-in.
     */
    fun openGroupInvite(code: String) {
        val state = _uiState.value
        if (!state.didAuthCheck || state.profile == null) {
            pendingInviteCode = code
            if (state.didAuthCheck) _uiState.update { it.copy(screen = Screen.AUTH) }
            return
        }
        _uiState.update { it.copy(screen = Screen.COMMUNITY, pendingGroupInvite = code) }
    }

    /** The user picked or changed their username. */
    fun onUsernameSaved(username: String) =
        _uiState.update { s -> s.copy(profile = s.profile?.copy(username = username)) }

    /** The Groups screen has taken the invite code. */
    fun clearGroupInvite() = _uiState.update { it.copy(pendingGroupInvite = null) }

    /** Called by [MainActivity] when a group invitation push notification is tapped. */
    fun openGroupInvitations() {
        val state = _uiState.value
        if (!state.didAuthCheck || state.profile == null) {
            pendingOpenGroups = true
            return
        }
        _uiState.update { it.copy(screen = Screen.COMMUNITY) }
    }

    private fun consumePendingInvite() {
        if (pendingOpenGroups) {
            pendingOpenGroups = false
            _uiState.update { it.copy(screen = Screen.COMMUNITY) }
        }
        val code = pendingInviteCode ?: return
        pendingInviteCode = null
        _uiState.update { it.copy(screen = Screen.COMMUNITY, pendingGroupInvite = code) }
    }

    /** Signs the user out and resets screen state that depends on an active session. */
    fun logout() {
        authRepository.signOut()
        clearSessionState()
    }

    /**
     * Permanently deletes the account (Settings → Delete account). [onDone]
     * gets null on success (the app returns to sign-in) or an error message.
     */
    fun deleteAccount(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            authRepository.deleteAccount()
                .onSuccess {
                    clearSessionState()
                    onDone(null)
                }
                .onFailure { e -> onDone(e.message ?: "Couldn't delete your account.") }
        }
    }

    /** Forgets everything tied to the signed-out account, including links waiting for sign-in. */
    private fun clearSessionState() {
        pendingDeepLinkDanceId = null
        pendingInviteCode = null
        pendingOpenGroups = false
        LocalUserFiles.clear(appContext)
        _uiState.update {
            it.copy(
                profile = null,
                screen = Screen.AUTH,
                selectedDanceItem = null,
                sharedDance = null,
                pendingGroupInvite = null,
                isUploadingProfilePhoto = false,
                profilePhotoUploadError = null,
                deepLinkLoading = false,
                deepLinkError = null
            )
        }
    }

    /** Opens the selected dance and moves the app into practice mode for that routine. */
    fun pickDance(item: DanceLibraryModel) {
        _uiState.update {
            it.copy(selectedDanceItem = item, screen = Screen.DANCE_PRACTICE)
        }
    }

    /** Returns to the home feed and clears any selected practice dance. */
    fun backToHome() {
        _uiState.update { it.copy(screen = Screen.HOME, selectedDanceItem = null, sharedDance = null) }
    }

    /**
     * Handles the system back gesture: goes to [Screen.backTarget].
     * Returns false on root screens so the system can close the app.
     */
    fun navigateBack(): Boolean {
        when (_uiState.value.screen.backTarget) {
            Screen.HOME -> backToHome()
            Screen.PROFILE -> openProfile()
            else -> return false
        }
        return true
    }

    /** Called when the welcome carousel is finished or skipped; it won't show again. */
    fun completeOnboarding() {
        _uiState.update { it.copy(onboardingSeen = true) }
        viewModelScope.launch { settings.setOnboardingSeen() }
    }

    /** Saves the light/dark choice from Settings. */
    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { settings.setThemeMode(mode.key) }
    }

    /** Opens the upload screen for submitting a new dance video. */
    fun openUpload() = _uiState.update { it.copy(screen = Screen.UPLOAD) }

    /** Opens the shared community discussion screen. */
    fun openCommunity() = _uiState.update { it.copy(screen = Screen.COMMUNITY) }

    /** Opens the profile section to view account details, stats, and uploads. */
    fun openProfile() = _uiState.update { it.copy(screen = Screen.PROFILE) }

    /** Opens the settings screen for notification and profile-related preferences. */
    fun openSettings() = _uiState.update { it.copy(screen = Screen.SETTINGS) }

    /** Opens the leaderboard screen to compare ranking metrics with other users. */
    fun openLeaderboard() = _uiState.update { it.copy(screen = Screen.LEADERBOARD) }

    /** Opens the personalized weekly challenges screen reached from the profile section. */
    fun openWeeklyChallenges() = _uiState.update { it.copy(screen = Screen.WEEKLY_CHALLENGES) }
}
