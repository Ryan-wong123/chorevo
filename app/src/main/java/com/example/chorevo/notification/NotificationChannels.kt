package com.example.chorevo.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val REMINDER_CHANNEL_ID = "daily_reminders"
    /** Push notifications for group invitations (sent by the notifyGroupInvitation function). */
    const val GROUP_INVITES_CHANNEL_ID = "group_invites"
    /** The ongoing notification shown while an uploaded dance is analysed in the background. */
    const val DANCE_ANALYSIS_CHANNEL_ID = "dance_analysis"

    /** Handles create. */
    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Daily reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Chorevo practice reminders"
        }

        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                GROUP_INVITES_CHANNEL_ID,
                "Group invitations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "When someone invites you to a dance group"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DANCE_ANALYSIS_CHANNEL_ID,
                "Dance analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while an uploaded dance is being analysed"
            }
        )
    }
}
