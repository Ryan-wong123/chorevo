package com.example.chorevo.core.ViewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.UserProfile

enum class Screen {
    AUTH, HOME, DANCE_PRACTICE, UPLOAD, COMMUNITY, PROFILE, LEADERBOARD, SETTINGS, WEEKLY_CHALLENGES;

    /**
     * Where the system back gesture goes from this screen, or null when back
     * should leave the app (home and sign-in are the roots).
     */
    val backTarget: Screen?
        get() = when (this) {
            DANCE_PRACTICE, UPLOAD, COMMUNITY, LEADERBOARD, PROFILE -> HOME
            SETTINGS, WEEKLY_CHALLENGES -> PROFILE
            AUTH, HOME -> null
        }
}

/** App colour scheme choice. Dark is the default look. */
enum class ThemeMode(val key: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SYSTEM("system", "System");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
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
    /**
     * Dance opened from a share link. The home feed shows its video first,
     * the way TikTok/Instagram open a shared post.
     */
    val sharedDance: DanceLibraryModel? = null,
    /** Invite code from a scanned group QR code or link, waiting for the Groups screen. */
    val pendingGroupInvite: String? = null,
    /** Light/dark choice from Settings; loaded from DataStore. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Whether the welcome carousel was already shown; null until loaded. */
    val onboardingSeen: Boolean? = null,
) {
    val displayName: String =
        profile?.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"

    val isSessionReady: Boolean
        get() = didAuthCheck && profile != null
}
