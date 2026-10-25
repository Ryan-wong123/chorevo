package com.example.chorevo.firebase.repository

import android.net.Uri
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.UserRole
import com.example.chorevo.notification.PushTokens
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FieldValue
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
    private val storage: FirebaseStorage,
    private val pushTokens: PushTokens
) {

    /** Handles sign up. */
    suspend fun signUp(
        email: String,
        password: String,
        displayName: String
    ): Result<UserProfile> = runCatching {
        val res = auth.createUserWithEmailAndPassword(email, password).await()
        val user = res.user ?: error("Signup failed")
        // Lets the user prove they own the address (and recover the account).
        runCatching { user.sendEmailVerification() }
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

        UserProfile(
            uid = user.uid,
            email = user.email,
            displayName = snap.getString("displayName")?.trim(),
            role = UserRole.USER,
            photoUrl = snap.getString("photoUrl"),
            username = snap.getString("username")?.takeUnless { it.isBlank() }
        )
    }

    /**
     * Permanently deletes the signed-in account. The deleteUserData Cloud
     * Function then removes the user's data and files. Firebase asks for a
     * recent sign-in first; that failure comes back as a friendly message.
     */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("You're not signed in.")
        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw IllegalStateException(
                "For your security, sign out and sign in again, then delete your account.", e
            )
        }
        pushTokens.unregister()
        auth.signOut()
    }

    /** Sends a password reset email; failures (such as no such account) are not reported. */
    suspend fun sendPasswordReset(email: String) {
        runCatching { auth.sendPasswordResetEmail(email).await() }
    }

    /** Handles sign out. */
    fun signOut() {
        pushTokens.unregister()
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
        val username = existing.getString("username")?.takeUnless { it.isBlank() }

        val displayName = explicitDisplayName?.trim()?.take(MAX_NAME_LENGTH).takeUnless { it.isNullOrBlank() }
            ?: storedDisplayName
            ?: fallbackDisplayName?.trim()?.take(MAX_NAME_LENGTH).takeUnless { it.isNullOrBlank() }
        val photoUrl = storedPhotoUrl ?: fallbackPhotoUrl?.takeUnless { it.isBlank() }

        // Profiles are readable by other users, so no email here (it comes from
        // Firebase Auth); older versions stored it, so remove it. The role is
        // never written by the app.
        val fields = mutableMapOf<String, Any?>(
            "uid" to user.uid,
            "email" to FieldValue.delete(),
            "lastSeen" to System.currentTimeMillis()
        )
        // Only write name/photo when there is a value, so merge never nulls them out.
        if (displayName != null) fields["displayName"] = displayName
        if (photoUrl != null) fields["photoUrl"] = photoUrl

        userRef.set(fields, SetOptions.merge()).await()
        // The leaderboard copy of name and photo is kept by a Cloud Function.

        return UserProfile(
            uid = user.uid,
            email = user.email ?: fallbackEmail,
            displayName = displayName,
            role = UserRole.USER,
            photoUrl = photoUrl,
            username = username
        )
    }

    private companion object {
        /** Same limit as firestore.rules (isName). */
        const val MAX_NAME_LENGTH = 60
    }
}
