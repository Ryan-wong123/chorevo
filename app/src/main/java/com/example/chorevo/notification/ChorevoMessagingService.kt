package com.example.chorevo.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.chorevo.MainActivity
import com.example.chorevo.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives push notifications. In the background the system shows them and a
 * tap opens [MainActivity] with the message data as extras; in the foreground
 * they arrive here and are shown the same way.
 */
@AndroidEntryPoint
class ChorevoMessagingService : FirebaseMessagingService() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var pushTokens: PushTokens

    override fun onNewToken(token: String) {
        auth.currentUser?.uid?.let { uid -> pushTokens.save(uid, token) }
    }

    // Permission is checked at the top of the method.
    @SuppressLint("MissingPermission")
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        NotificationChannels.create(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data.forEach { (k, v) -> putExtra(k, v) }
        }
        val requestCode = (message.data["groupId"] ?: title).hashCode()
        val pending = PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.GROUP_INVITES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(this).notify(requestCode, notification)
    }

    companion object {
        /** `type` in the message data of a group invitation push. */
        const val TYPE_GROUP_INVITATION = "group_invitation"
    }
}
