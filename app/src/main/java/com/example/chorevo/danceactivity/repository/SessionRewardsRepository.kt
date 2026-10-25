package com.example.chorevo.danceactivity.repository

import com.example.chorevo.danceactivity.SessionRewards
import com.example.chorevo.danceactivity.sessionRewardsFrom
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the rewards the server computed for a practice session. */
@Singleton
class SessionRewardsRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    /** Emits null until users/{uid}/sessionRewards/{sessionId} exists, then its rewards. */
    fun observe(uid: String, sessionId: String): Flow<SessionRewards?> = callbackFlow {
        val reg = db.collection("users").document(uid)
            .collection("sessionRewards").document(sessionId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val data = snap?.takeIf { it.exists() }?.data
                trySend(data?.let { sessionRewardsFrom(sessionId, it) })
            }
        awaitClose { reg.remove() }
    }
}
