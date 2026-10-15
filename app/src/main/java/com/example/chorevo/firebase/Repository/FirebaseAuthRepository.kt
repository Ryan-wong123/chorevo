package com.example.chorevo.firebase.Repository

import android.net.Uri
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.UserRole
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
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
        syncUserProfile(
            user = user,
            fallbackEmail = email,
            explicitDisplayName = displayName.trim()
        )
    }

    /** Handles sign in. */
    suspend fun signIn(
        email: String,
        password: String
    ): Result<UserProfile> = runCatching {
        val res = auth.signInWithEmailAndPassword(email, password).await()
        val user = res.user ?: error("Login failed")
        syncUserProfile(user = user, fallbackEmail = email)
    }

    /** Handles Google sign-in using a Google ID token. */
    suspend fun signInWithGoogle(idToken: String): Result<UserProfile> = runCatching {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val res = auth.signInWithCredential(credential).await()
        val user = res.user ?: error("Google sign-in failed")
        syncUserProfile(
            user = user,
            fallbackDisplayName = user.displayName?.trim()
        )
    }

    /** Returns current user profile. */
    suspend fun getCurrentUserProfile(): UserProfile? {
        val user = auth.currentUser ?: return null

        return syncUserProfile(
            user = user,
            fallbackDisplayName = user.displayName?.trim()
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

    /**
     * Creates or refreshes the Firestore user record, then returns the normalized profile.
     *
     * [explicitDisplayName] (chosen at sign-up) is always saved. The fallback
     * name and photo from the auth provider only fill in values the stored
     * profile does not have yet, so a name or photo the user set in the app is
     * never overwritten or blanked on sign-in or app start.
     */
    private suspend fun syncUserProfile(
        user: FirebaseUser,
        fallbackEmail: String? = user.email,
        explicitDisplayName: String? = null,
        fallbackDisplayName: String? = null,
        fallbackPhotoUrl: String? = user.photoUrl?.toString()
    ): UserProfile {
        val userRef = db.collection("users").document(user.uid)
        val existing = userRef.get().await()
        val storedDisplayName = existing.getString("displayName")?.trim().takeUnless { it.isNullOrBlank() }
        val storedPhotoUrl = existing.getString("photoUrl")?.takeUnless { it.isBlank() }

        val displayName = explicitDisplayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: storedDisplayName
            ?: fallbackDisplayName?.trim().takeUnless { it.isNullOrBlank() }
        val photoUrl = storedPhotoUrl ?: fallbackPhotoUrl?.takeUnless { it.isBlank() }

        val fields = mutableMapOf<String, Any?>(
            "uid" to user.uid,
            "email" to (user.email ?: fallbackEmail),
            "role" to UserRole.USER.name,
            "lastSeen" to System.currentTimeMillis()
        )
        // Only write name/photo when there is a value, so merge never nulls them out.
        if (displayName != null) fields["displayName"] = displayName
        if (photoUrl != null) fields["photoUrl"] = photoUrl

        userRef.set(fields, SetOptions.merge()).await()

        syncLeaderboardProfile(
            uid = user.uid,
            displayName = displayName,
            photoUrl = photoUrl
        )

        return UserProfile(
            uid = user.uid,
            email = user.email ?: fallbackEmail,
            displayName = displayName,
            role = UserRole.USER,
            photoUrl = photoUrl
        )
    }
}
