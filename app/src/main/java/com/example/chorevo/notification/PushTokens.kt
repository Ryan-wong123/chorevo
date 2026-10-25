package com.example.chorevo.notification

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's push token, kept at users/{uid}/fcmTokens/{token} so Cloud
 * Functions can notify the user. The server drops tokens that stop working.
 */
@Singleton
class PushTokens @Inject constructor(
    private val messaging: FirebaseMessaging,
    private val firestore: FirebaseFirestore
) {
    private companion object {
        const val TAG = "PushTokens"
    }

    /** Saves the current token for [uid]; call after sign-in. */
    fun register(uid: String) {
        runCatching {
            messaging.token
                .addOnSuccessListener { token -> save(uid, token) }
                .addOnFailureListener { e -> Log.w(TAG, "No push token", e) }
        }.onFailure { e -> Log.w(TAG, "Push unavailable", e) }
    }

    fun save(uid: String, token: String) {
        runCatching {
            firestore
                .collection("users").document(uid)
                .collection("fcmTokens").document(token)
                .set(mapOf("token" to token, "platform" to "android", "updatedAt" to System.currentTimeMillis()))
                .addOnFailureListener { e -> Log.w(TAG, "Couldn't save push token", e) }
        }
    }

    /** On sign-out: retires this device's token so the old account stops getting pushes here. */
    fun unregister() {
        runCatching { messaging.deleteToken() }
    }
}
