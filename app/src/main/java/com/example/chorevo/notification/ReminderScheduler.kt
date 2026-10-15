package com.example.chorevo.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.first
import java.util.Calendar

class ReminderScheduler(
    private val context: Context,
    private val prefs: NotificationPreferencesDataStore
) {
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Handles reschedule from preferences. */
    suspend fun rescheduleFromPreferences() {
        val enabled = prefs.pushEnabledFlow.first()
        if (!enabled) {
            cancelDaily()
            return
        }

        val (hour, minute) = prefs.notificationTimeFlow.first()
        scheduleNextDaily(hour, minute)
    }

    /** Handles schedule next daily. */
    fun scheduleNextDaily(hour: Int, minute: Int) {
        val triggerAt = computeNextTrigger(hour, minute)
        val pi = dailyPendingIntent(hour, minute)

        Log.d("ReminderScheduler", "canExact=${canUseExactAlarms()}")

        if (canUseExactAlarms()) {
            Log.d("ReminderScheduler", "Scheduling DAILY exact: $triggerAt")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pi
            )
        } else {
            Log.d("ReminderScheduler", "Scheduling DAILY inexact: $triggerAt")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pi
            )
        }
    }

    /** Handles schedule repeating test in minutes. */
    fun scheduleRepeatingTestInMinutes(intervalMinutes: Int) {
        val triggerAt = System.currentTimeMillis() + intervalMinutes * 60_000L
        val pi = testPendingIntent(intervalMinutes)

        alarmManager.cancel(pi)

        if (canUseExactAlarms()) {
            Log.d("ReminderScheduler", "Scheduling TEST exact ($intervalMinutes): $triggerAt")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pi
            )
        } else {
            Log.d("ReminderScheduler", "Scheduling TEST inexact ($intervalMinutes): $triggerAt")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pi
            )
        }
    }

    /** Starts repeating test alarms. */
    fun startRepeatingTestAlarms() {
        scheduleRepeatingTestInMinutes(5)
        scheduleRepeatingTestInMinutes(60)
    }

    /** Checks whether cel repeating test alarms. */
    fun cancelRepeatingTestAlarms() {
        alarmManager.cancel(testPendingIntent(5))
        alarmManager.cancel(testPendingIntent(60))
    }

    /** Checks whether use exact alarms. */
    fun canUseExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** Opens exact alarm settings. */
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    /** Checks whether cel daily. */
    fun cancelDaily() {
        alarmManager.cancel(dailyPendingIntent())
    }

    /** Checks whether cel all. */
    fun cancelAll() {
        cancelDaily()
        cancelRepeatingTestAlarms()
    }

    /**
     * Daily reminder intent. The chosen time travels with the alarm so the
     * receiver can re-arm the next day at the same time. Extras do not affect
     * PendingIntent matching, so [cancelDaily] still cancels it.
     */
    private fun dailyPendingIntent(hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("notification_type", "daily")
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
        }
        return PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Handles test pending intent. */
    private fun testPendingIntent(intervalMinutes: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("notification_type", "test")
            putExtra("test_minutes", intervalMinutes)
        }

        val requestCode = when (intervalMinutes) {
            5 -> 2005
            60 -> 2060
            else -> 3000 + intervalMinutes
        }

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Computes next trigger. */
    private fun computeNextTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }


        if (next.timeInMillis <= now.timeInMillis) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }

    companion object {
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val DEFAULT_HOUR = 9
        const val DEFAULT_MINUTE = 0
    }
}
