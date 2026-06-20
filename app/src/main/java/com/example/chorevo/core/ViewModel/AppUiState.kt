package com.example.chorevo.core.ViewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.UserProfile

enum class Screen {
    AUTH, HOME, DANCE_PRACTICE, UPLOAD, COMMUNITY, PROFILE, LEADERBOARD, SETTINGS, WEEKLY_CHALLENGES
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
    val profilePhotoUploadError: String? = null,

    // ── Deep-link state ───────────────────────────────────────────────────────
    /** True while a shared dance link is being fetched from Firestore. */
    val deepLinkLoading: Boolean = false,
    /**
     * Non-null when a deep-link fetch failed.  The UI should display this as a
     * snackbar / toast and then call [AppViewModel.clearDeepLinkError].
     */
    val deepLinkError: String? = null,
) {
    val displayName: String =
        profile?.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"

    val isSessionReady: Boolean
        get() = didAuthCheck && profile != null
}
