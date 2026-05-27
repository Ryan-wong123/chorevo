package com.example.chorevo.community.Repository

import android.util.Log
import com.example.chorevo.community.CommunityPost
import com.example.chorevo.community.CommunityReply
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    fun observePosts(): Flow<List<CommunityPost>> = callbackFlow {
        val reg = firestore.collection(POSTS_COLLECTION)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("CommunityRepository", "observePosts failed: ${err.message}", err)
                    return@addSnapshotListener
                }

                val posts = snap?.documents.orEmpty()
                    .mapNotNull { it.toCommunityPostOrNull() }

                trySend(posts)
            }

        awaitClose { reg.remove() }
    }

    fun observeReplies(postId: String): Flow<List<CommunityReply>> = callbackFlow {
        val reg = firestore.collection(POSTS_COLLECTION)
            .document(postId)
            .collection(REPLIES_COLLECTION)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("CommunityRepository", "observeReplies failed: ${err.message}", err)
                    return@addSnapshotListener
                }

                val replies = snap?.documents.orEmpty()
                    .mapNotNull { it.toCommunityReplyOrNull(postId) }

                trySend(replies)
            }

        awaitClose { reg.remove() }
    }

    suspend fun createPost(
        uid: String,
        displayName: String,
        photoUrl: String?,
        title: String,
        body: String
    ): CommunityPost? {
        val cleanTitle = title.trim().take(120)
        val cleanBody = body.trim().take(1_500)
        if (uid.isBlank() || cleanTitle.isBlank() || cleanBody.isBlank()) return null

        val doc = firestore.collection(POSTS_COLLECTION).document()
        val post = CommunityPost(
            id = doc.id,
            uid = uid,
            displayName = displayName.trim().ifBlank { "User" },
            photoUrl = photoUrl,
            title = cleanTitle,
            body = cleanBody,
            createdAt = System.currentTimeMillis()
        )

        doc.set(
            mapOf(
                "id" to post.id,
                "uid" to post.uid,
                "displayName" to post.displayName,
                "photoUrl" to post.photoUrl,
                "title" to post.title,
                "body" to post.body,
                "createdAt" to post.createdAt
            )
        ).await()
        return post
    }

    suspend fun createReply(
        postId: String,
        uid: String,
        displayName: String,
        photoUrl: String?,
        body: String
    ): CommunityReply? {
        val cleanBody = body.trim().take(500)
        if (postId.isBlank() || uid.isBlank() || cleanBody.isBlank()) return null

        val doc = firestore.collection(POSTS_COLLECTION)
            .document(postId)
            .collection(REPLIES_COLLECTION)
            .document()

        val reply = CommunityReply(
            id = doc.id,
            postId = postId,
            uid = uid,
            displayName = displayName.trim().ifBlank { "User" },
            photoUrl = photoUrl,
            body = cleanBody,
            createdAt = System.currentTimeMillis()
        )

        doc.set(
            mapOf(
                "id" to reply.id,
                "postId" to reply.postId,
                "uid" to reply.uid,
                "displayName" to reply.displayName,
                "photoUrl" to reply.photoUrl,
                "body" to reply.body,
                "createdAt" to reply.createdAt
            )
        ).await()
        return reply
    }

    private fun DocumentSnapshot.toCommunityPostOrNull(): CommunityPost? {
        val title = getString("title")?.trim().orEmpty()
        val body = getString("body")?.trim().orEmpty()
        if (title.isBlank() || body.isBlank()) return null

        return CommunityPost(
            id = getString("id") ?: id,
            uid = getString("uid").orEmpty(),
            displayName = getString("displayName")?.trim().takeUnless { it.isNullOrBlank() } ?: "User",
            photoUrl = getString("photoUrl"),
            title = title,
            body = body,
            createdAt = getLong("createdAt") ?: 0L
        )
    }

    private fun DocumentSnapshot.toCommunityReplyOrNull(postId: String): CommunityReply? {
        val body = getString("body")?.trim().orEmpty()
        if (body.isBlank()) return null

        return CommunityReply(
            id = getString("id") ?: id,
            postId = getString("postId") ?: postId,
            uid = getString("uid").orEmpty(),
            displayName = getString("displayName")?.trim().takeUnless { it.isNullOrBlank() } ?: "User",
            photoUrl = getString("photoUrl"),
            body = body,
            createdAt = getLong("createdAt") ?: 0L
        )
    }

    private companion object {
        const val POSTS_COLLECTION = "communityPosts"
        const val REPLIES_COLLECTION = "replies"
    }
}
