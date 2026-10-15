package com.example.chorevo.notification

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationPrefsDataStore by preferencesDataStore(
    name = "notification_preferences"
)

@Singleton
class NotificationPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val NOTIFICATION_HOUR = intPreferencesKey("notification_hour")
        val NOTIFICATION_MINUTE = intPreferencesKey("notification_minute")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
    }

    /** True once the welcome carousel has been finished or skipped on this device. */
    val onboardingSeenFlow: Flow<Boolean> =
        context.notificationPrefsDataStore.data.map { prefs -> prefs[Keys.ONBOARDING_SEEN] ?: false }

    /** Remembers that the welcome carousel was shown. */
    suspend fun setOnboardingSeen() {
        context.notificationPrefsDataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_SEEN] = true
        }
    }

    /** Stored theme key ("dark", "light" or "system"); null until the user picks one. */
    val themeModeFlow: Flow<String?> =
        context.notificationPrefsDataStore.data.map { prefs -> prefs[Keys.THEME_MODE] }

    /** Saves the theme choice. */
    suspend fun setThemeMode(key: String) {
        context.notificationPrefsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = key
        }
    }

    val pushEnabledFlow: Flow<Boolean> =
        context.notificationPrefsDataStore.data.map { prefs ->
            prefs[Keys.PUSH_ENABLED] ?: false
        }

    val notificationHourFlow: Flow<Int> =
        context.notificationPrefsDataStore.data.map { prefs ->
            prefs[Keys.NOTIFICATION_HOUR] ?: 9
        }

    val notificationMinuteFlow: Flow<Int> =
        context.notificationPrefsDataStore.data.map { prefs ->
            prefs[Keys.NOTIFICATION_MINUTE] ?: 0
        }

    val notificationTimeFlow: Flow<Pair<Int, Int>> =
        context.notificationPrefsDataStore.data.map { prefs ->
            val hour = prefs[Keys.NOTIFICATION_HOUR] ?: 9
            val minute = prefs[Keys.NOTIFICATION_MINUTE] ?: 0
            hour to minute
        }

    /** Updates push enabled. */
    suspend fun setPushEnabled(enabled: Boolean) {
        context.notificationPrefsDataStore.edit { prefs ->
            prefs[Keys.PUSH_ENABLED] = enabled
        }
    }

    /** Updates notification time. */
    suspend fun setNotificationTime(hour: Int, minute: Int) {
        context.notificationPrefsDataStore.edit { prefs ->
            prefs[Keys.NOTIFICATION_HOUR] = hour
            prefs[Keys.NOTIFICATION_MINUTE] = minute
        }
    }
}
