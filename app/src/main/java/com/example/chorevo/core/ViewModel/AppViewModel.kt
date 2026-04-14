package com.example.chorevo.core.ViewModel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
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
    private val settings: NotificationPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val reminderScheduler = ReminderScheduler(appContext, settings)

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
            }
        }

        viewModelScope.launch {
            settings.pushEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(pushEnabled = enabled) }
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
    }

    /** Signs the user out and resets screen state that depends on an active session. */
    fun logout() {
        authRepository.signOut()
        _uiState.update {
            it.copy(
                profile = null,
                screen = Screen.AUTH,
                selectedDanceItem = null,
                isUploadingProfilePhoto = false,
                profilePhotoUploadError = null
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
        _uiState.update { it.copy(screen = Screen.HOME, selectedDanceItem = null) }
    }

    /** Opens the upload screen for submitting a new dance video. */
    fun openUpload() = _uiState.update { it.copy(screen = Screen.UPLOAD) }

    /** Opens the profile section to view account details, stats, and uploads. */
    fun openProfile() = _uiState.update { it.copy(screen = Screen.PROFILE) }

    /** Opens the settings screen for notification and profile-related preferences. */
    fun openSettings() = _uiState.update { it.copy(screen = Screen.SETTINGS) }

    /** Opens the leaderboard screen to compare ranking metrics with other users. */
    fun openLeaderboard() = _uiState.update { it.copy(screen = Screen.LEADERBOARD) }
}
