package com.example.chorevo.firebase.Repository

import android.net.Uri
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.UserRole
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage
) {

    /** Handles sign up. */
    suspend fun signUp(
        email: String,
        password: String,
        displayName: String
    ): Result<UserProfile> = runCatching {
        val res = auth.createUserWithEmailAndPassword(email, password).await()
        val user = res.user ?: error("Signup failed")

        db.collection("users")
            .document(user.uid)
            .set(
                mapOf(
                    "uid" to user.uid,
                    "email" to email,
                    "role" to UserRole.USER.name,
                    "displayName" to displayName.trim(),
                    "photoUrl" to null,
                    "lastSeen" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()

        UserProfile(
            uid = user.uid,
            email = email,
            displayName = displayName.trim(),
            role = UserRole.USER,
            photoUrl = null
        )
    }

    /** Handles sign in. */
    suspend fun signIn(
        email: String,
        password: String
    ): Result<UserProfile> = runCatching {
        val res = auth.signInWithEmailAndPassword(email, password).await()
        val user = res.user ?: error("Login failed")

        val snap = db.collection("users")
            .document(user.uid)
            .get()
            .await()

        db.collection("users")
            .document(user.uid)
            .set(mapOf("lastSeen" to System.currentTimeMillis()), SetOptions.merge())
            .await()

        UserProfile(
            uid = user.uid,
            email = user.email ?: email,
            displayName = snap.getString("displayName")?.trim(),
            role = UserRole.USER,
            photoUrl = snap.getString("photoUrl")
        )
    }

    /** Returns current user profile. */
    suspend fun getCurrentUserProfile(): UserProfile? {
        val user = auth.currentUser ?: return null

        val ref = db.collection("users").document(user.uid)
        val snap = ref.get().await()

        ref.set(
            mapOf(
                "lastSeen" to System.currentTimeMillis(),
                "role" to UserRole.USER.name
            ),
            SetOptions.merge()
        ).await()

        return UserProfile(
            uid = user.uid,
            email = user.email,
            displayName = snap.getString("displayName")?.trim(),
            role = UserRole.USER,
            photoUrl = snap.getString("photoUrl")
        )
    }

    /** Uploads profile photo. */
    suspend fun uploadProfilePhoto(imageUri: Uri): Result<UserProfile> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")

        val photoRef = storage.reference.child("profilePhotos/${user.uid}/profile.jpg")
        photoRef.putFile(imageUri).await()
        val downloadUrl = photoRef.downloadUrl.await().toString()

        db.collection("users")
            .document(user.uid)
            .set(
                mapOf(
                    "photoUrl" to downloadUrl,
                    "lastSeen" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()

        val snap = db.collection("users")
            .document(user.uid)
            .get()
            .await()

        syncLeaderboardProfile(
            uid = user.uid,
            displayName = snap.getString("displayName")?.trim(),
            photoUrl = downloadUrl
        )

        UserProfile(
            uid = user.uid,
            email = user.email,
            displayName = snap.getString("displayName")?.trim(),
            role = UserRole.USER,
            photoUrl = snap.getString("photoUrl")
        )
    }

    /** Handles sync leaderboard profile. */
    private suspend fun syncLeaderboardProfile(
        uid: String,
        displayName: String?,
        photoUrl: String?
    ) {
        db.collection("leaderboardStats")
            .document(uid)
            .set(
                mapOf(
                    "uid" to uid,
                    "displayName" to (displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"),
                    "photoUrl" to photoUrl,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()
    }

    /** Handles sign out. */
    fun signOut() {
        auth.signOut()
    }
}
