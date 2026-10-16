package com.example.chorevo.firebase.Repository

import com.example.chorevo.firebase.Usernames
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A user found by username, for invites. */
data class UserSearchResult(
    val uid: String,
    val username: String,
    val displayName: String,
    val photoUrl: String?
)

/**
 * Unique usernames: `usernames/{handle}` holds `{uid}` and the user's profile
 * holds `username`. Both are written in one batch (the rules require it).
 */
@Singleton
class UsernameRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    suspend fun isAvailable(handle: String): Result<Boolean> = runCatching {
        !firestore.collection(USERNAMES).document(handle).get().await().exists()
    }

    /** A free username based on [displayName]: the plain suggestion, else with a number. */
    suspend fun suggest(displayName: String?): String {
        val base = Usernames.suggestionFor(displayName)
        if (isAvailable(base).getOrDefault(false)) return base
        repeat(5) {
            val candidate = Usernames.withNumber(base, Random.nextInt(10, 10_000))
            if (isAvailable(candidate).getOrDefault(false)) return candidate
        }
        return base
    }

    /** Claims [handle] for [uid], releasing [current] if it changes. */
    suspend fun claim(uid: String, handle: String, current: String?): Result<String> = runCatching {
        require(Usernames.isValid(handle)) { Usernames.problem(handle) ?: "Invalid username" }
        if (handle == current) return@runCatching handle
        val batch = firestore.batch()
        batch.set(
            firestore.collection(USERNAMES).document(handle),
            mapOf("uid" to uid, "createdAt" to System.currentTimeMillis())
        )
        batch.set(firestore.collection(USERS).document(uid), mapOf("username" to handle), SetOptions.merge())
        if (!current.isNullOrBlank()) batch.delete(firestore.collection(USERNAMES).document(current))
        try {
            batch.commit().await()
        } catch (e: Exception) {
            // The rules reject a taken handle; say so plainly.
            if (isAvailable(handle).getOrDefault(true)) throw e
            throw IllegalStateException("@$handle is taken")
        }
        handle
    }

    /** Users whose username starts with [prefix] (up to 10), with their names and photos. */
    suspend fun search(prefix: String): Result<List<UserSearchResult>> = runCatching {
        val p = Usernames.normalize(prefix)
        if (p.isEmpty()) return@runCatching emptyList()
        val handles = firestore.collection(USERNAMES)
            .orderBy(FieldPath.documentId())
            .startAt(p)
            .endAt(p + "")
            .limit(SEARCH_LIMIT)
            .get()
            .await()
            .documents
        coroutineScope {
            handles.map { h ->
                async {
                    val uid = h.getString("uid") ?: return@async null
                    val user = runCatching { firestore.collection(USERS).document(uid).get().await() }.getOrNull()
                    UserSearchResult(
                        uid = uid,
                        username = h.id,
                        displayName = user?.getString("displayName")?.takeUnless { it.isBlank() } ?: h.id,
                        photoUrl = user?.getString("photoUrl")?.takeUnless { it.isBlank() }
                    )
                }
            }.awaitAll().filterNotNull()
        }
    }

    private companion object {
        const val USERS = "users"
        const val USERNAMES = "usernames"
        const val SEARCH_LIMIT = 10L
    }
}
