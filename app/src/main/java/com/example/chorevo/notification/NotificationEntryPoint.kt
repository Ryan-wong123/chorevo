package com.example.chorevo.notification

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives the reminder BroadcastReceivers their dependencies. (@AndroidEntryPoint
 * receivers must call the abstract super.onReceive, which Kotlin can't do.)
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationEntryPoint {
    fun reminderScheduler(): ReminderScheduler
    fun notificationPreferences(): NotificationPreferencesDataStore

    companion object {
        fun from(context: Context): NotificationEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, NotificationEntryPoint::class.java)
    }
}
