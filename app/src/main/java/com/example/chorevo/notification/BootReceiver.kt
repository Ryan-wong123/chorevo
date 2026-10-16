package com.example.chorevo.notification


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    /** Handles receive events. */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // Keep the process alive until rescheduling finishes.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = NotificationPreferencesDataStore(context)
                    val scheduler = ReminderScheduler(context, prefs)

                    val pushEnabled = prefs.pushEnabledFlow.firstOrNull() ?: false
                    if (pushEnabled) {
                        val hour = prefs.notificationHourFlow.firstOrNull() ?: 9
                        val minute = prefs.notificationMinuteFlow.firstOrNull() ?: 0
                        scheduler.scheduleNextDaily(hour, minute)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
