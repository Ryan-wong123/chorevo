package com.example.chorevo.core.ViewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.UserProfile

enum class Screen {
    AUTH, HOME, DANCE_PRACTICE, UPLOAD, PROFILE, LEADERBOARD, SETTINGS
}

/**
 * Holds app-wide UI state such as the current screen, signed-in user, and notification settings.
 */
data class AppUiState(
    val didAuthCheck: Boolean = false,
    val profile: UserProfile? = null,
    val screen: Screen = Screen.AUTH,
    val selectedDanceItem: DanceLibraryModel? = null,
    val pushEnabled: Boolean = false,
    val notificationHour: Int = 9,
    val notificationMinute: Int = 0,
    val isUploadingProfilePhoto: Boolean = false,
    val profilePhotoUploadError: String? = null
) {
    val displayName: String =
        profile?.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"

    val isSessionReady: Boolean
        get() = didAuthCheck && profile != null
}
