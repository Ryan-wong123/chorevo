package com.example.chorevo.notification

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

/**
 * This device's push token, kept at users/{uid}/fcmTokens/{token} so Cloud
 * Functions can notify the user. The server drops tokens that stop working.
 */
object PushTokens {
    private const val TAG = "PushTokens"

    /** Saves the current token for [uid]; call after sign-in. */
    fun register(uid: String) {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> save(uid, token) }
                .addOnFailureListener { e -> Log.w(TAG, "No push token", e) }
        }.onFailure { e -> Log.w(TAG, "Push unavailable", e) }
    }

    fun save(uid: String, token: String) {
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("fcmTokens").document(token)
                .set(mapOf("token" to token, "platform" to "android", "updatedAt" to System.currentTimeMillis()))
                .addOnFailureListener { e -> Log.w(TAG, "Couldn't save push token", e) }
        }
    }

    /** On sign-out: retires this device's token so the old account stops getting pushes here. */
    fun unregister() {
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }
}
