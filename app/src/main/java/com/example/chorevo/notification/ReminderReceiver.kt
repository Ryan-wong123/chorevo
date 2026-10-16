package com.example.chorevo.notification

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chorevo.MainActivity
import com.example.chorevo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    /** Handles receive events. */
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("ReminderReceiver", "onReceive called")

        NotificationChannels.create(context)

        val type = intent?.getStringExtra("notification_type") ?: "daily"
        val testMinutes = intent?.getIntExtra("test_minutes", -1) ?: -1

        val title: String
        val body: String
        val notificationId: Int

        if (type == "test" && testMinutes > 0) {
            title = "Test reminder"
            body = "Repeating test notification every $testMinutes minute(s)."
            notificationId = 1000 + testMinutes
        } else {
            title = "Practice reminder"
            body = "Time for your dance practice."
            notificationId = 1001
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            3001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.REMINDER_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val hasPermission = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Log.d("ReminderReceiver", "notification posted")
        }


        val scheduler = ReminderScheduler(
            context,
            NotificationPreferencesDataStore(context)
        )

        when {
            type == "daily" -> {
                val hour = intent?.getIntExtra(ReminderScheduler.EXTRA_HOUR, ReminderScheduler.DEFAULT_HOUR)
                    ?: ReminderScheduler.DEFAULT_HOUR
                val minute = intent?.getIntExtra(ReminderScheduler.EXTRA_MINUTE, ReminderScheduler.DEFAULT_MINUTE)
                    ?: ReminderScheduler.DEFAULT_MINUTE

                scheduler.scheduleNextDaily(hour, minute)
            }

            type == "test" && testMinutes > 0 -> {
                scheduler.scheduleRepeatingTestInMinutes(testMinutes)
            }
        }
    }
}
