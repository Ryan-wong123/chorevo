package com.example.chorevo.community.Repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.chorevo.community.DanceGroup
import com.example.chorevo.community.GroupInvitation
import com.example.chorevo.community.GroupMember
import com.example.chorevo.community.GroupPost
import com.example.chorevo.community.GroupPostKind
import com.example.chorevo.community.GroupReply
import com.example.chorevo.community.INVITE_CODE_ALPHABET
import com.example.chorevo.community.INVITE_CODE_LENGTH
import com.example.chorevo.community.invitationId
import com.example.chorevo.community.isValidGroupName
import com.example.chorevo.core.VideoFrames
import com.example.chorevo.core.putFileWithProgress
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.UserSearchResult
import com.example.chorevo.videouploads.VideoCompressor
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout (see firestore.rules):
 * - groups/{groupId}: name, description, visibility, owner, memberCount (server), inviteCode (private)
 * - groups/{groupId}/members/{uid}: role owner|member
 * - groups/{groupId}/posts/{postId}: text, a shared dance, or a group-only video
 * - groups/{groupId}/posts/{postId}/replies/{replyId}
 * - groupInvites/{code}: groupId, so a code opens a private group
 * Group-only videos live in Storage at groupVideos/{uid}/ and groupThumbnails/{uid}/.
 */
@Singleton
class GroupsRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val videoCompressor: VideoCompressor,
    private val videoFrames: VideoFrames
) {
    private val random = SecureRandom()

    private fun groupRef(groupId: String) = firestore.collection(GROUPS).document(groupId)

    // ── Lists ───────────────────────────────────────────────────────────

    /** Groups the user belongs to, newest membership first. */
    fun observeMyGroups(uid: String): Flow<List<DanceGroup>> = callbackFlow {
        val reg = firestore.collectionGroup(MEMBERS)
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "observeMyGroups failed: ${err.message}", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val memberships = snap?.documents.orEmpty()
                    .sortedByDescending { it.getLong("joinedAt") ?: 0L }
                val ids = memberships.mapNotNull { it.reference.parent.parent?.id }.distinct()
                launch {
                    val groups = coroutineScope {
                        ids.map { id ->
                            async { runCatching { groupRef(id).get().await().toGroupOrNull() }.getOrNull() }
                        }.awaitAll()
                    }
                    trySend(groups.filterNotNull())
                }
            }
        awaitClose { reg.remove() }
    }

    /** Public groups: the biggest first, or those whose name starts with [search]. */
    suspend fun loadPublicGroups(search: String): Result<List<DanceGroup>> = runCatching {
        val prefix = search.trim().lowercase()
        var q: Query = firestore.collection(GROUPS)
            .whereEqualTo("visibility", DanceGroup.VISIBILITY_PUBLIC)
        q = if (prefix.isEmpty()) {
            q.orderBy("memberCount", Query.Direction.DESCENDING)
        } else {
            q.orderBy("nameLower")
                .whereGreaterThanOrEqualTo("nameLower", prefix)
                .whereLessThan("nameLower", prefix + "")
        }
        q.limit(DISCOVER_LIMIT).get().await().documents.mapNotNull { it.toGroupOrNull() }
    }

    // ── One group ───────────────────────────────────────────────────────

    fun observeGroup(groupId: String): Flow<DanceGroup?> = callbackFlow {
        val reg = groupRef(groupId).addSnapshotListener { snap, err ->
            // A private group the user just left (or a deleted group) can't be read.
            trySend(if (err != null) null else snap?.toGroupOrNull())
        }
        awaitClose { reg.remove() }
    }

    /** The user's membership in the group, or null when they aren't a member. */
    fun observeMembership(groupId: String, uid: String): Flow<GroupMember?> = callbackFlow {
        val reg = groupRef(groupId).collection(MEMBERS).document(uid)
            .addSnapshotListener { snap, err ->
                trySend(if (err != null) null else snap?.takeIf { it.exists() }?.toMember())
            }
        awaitClose { reg.remove() }
    }

    fun observeMembers(groupId: String): Flow<List<GroupMember>> = callbackFlow {
        val reg = groupRef(groupId).collection(MEMBERS)
            .orderBy("joinedAt")
            .limit(MEMBERS_LIMIT)
            .addSnapshotListener { snap, err ->
                trySend(if (err != null) emptyList() else snap?.documents.orEmpty().map { it.toMember() })
            }
        awaitClose { reg.remove() }
    }

    fun observePosts(groupId: String): Flow<List<GroupPost>> = callbackFlow {
        val reg = groupRef(groupId).collection(POSTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(POSTS_LIMIT)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "observePosts failed: ${err.message}", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snap?.documents.orEmpty().mapNotNull { it.toPostOrNull(groupId) })
            }
        awaitClose { reg.remove() }
    }

    fun observeReplies(groupId: String, postId: String): Flow<List<GroupReply>> = callbackFlow {
        val reg = groupRef(groupId).collection(POSTS).document(postId).collection(REPLIES)
            .orderBy("createdAt")
            .limit(REPLIES_LIMIT)
            .addSnapshotListener { snap, err ->
                trySend(if (err != null) emptyList() else snap?.documents.orEmpty().mapNotNull { it.toReplyOrNull(postId) })
            }
        awaitClose { reg.remove() }
    }

    // ── Invitations by username ─────────────────────────────────────────

    /** Invitations sent to [uid], newest first. */
    fun observeInvitations(uid: String): Flow<List<GroupInvitation>> = callbackFlow {
        val reg = firestore.collection(INVITATIONS)
            .whereEqualTo("inviteeUid", uid)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w(TAG, "observeInvitations failed", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents.orEmpty().mapNotNull { d ->
                    d.toObject(GroupInvitation::class.java)?.copy(id = d.id)
                }.sortedByDescending { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /** Invites [invitee] to [group] from [inviter], who must be a member. */
    suspend fun sendInvitation(group: DanceGroup, inviter: Author, invitee: UserSearchResult): Result<Unit> = runCatching {
        val ref = firestore.collection(INVITATIONS).document(invitationId(group.id, invitee.uid))
        ref.set(
            mapOf(
                "groupId" to group.id,
                "groupName" to group.name,
                "inviteeUid" to invitee.uid,
                "inviteeUsername" to invitee.username,
                "inviterUid" to inviter.uid,
                "inviterName" to inviter.displayName,
                "createdAt" to System.currentTimeMillis()
            )
        ).await()
    }

    /** Joins the invitation's group and removes the invitation, in one batch. */
    suspend fun acceptInvitation(invitation: GroupInvitation, me: Author): Result<String> = runCatching {
        val groupRef = groupRef(invitation.groupId)
        val invitationRef = firestore.collection(INVITATIONS).document(invitation.id)
        val batch = firestore.batch()
        batch.set(
            groupRef.collection(MEMBERS).document(me.uid),
            memberData(me.uid, me.displayName, me.photoUrl, GroupMember.ROLE_MEMBER, System.currentTimeMillis())
        )
        batch.delete(invitationRef)
        try {
            batch.commit().await()
        } catch (e: Exception) {
            // Already a member (joined another way): just clear the invitation.
            val alreadyMember = runCatching {
                groupRef.collection(MEMBERS).document(me.uid).get().await().exists()
            }.getOrDefault(false)
            if (!alreadyMember) {
                val groupGone = runCatching { !groupRef.get().await().exists() }.getOrDefault(false)
                if (groupGone) {
                    runCatching { invitationRef.delete().await() }
                    throw IllegalStateException("This group no longer exists.")
                }
                throw e
            }
            invitationRef.delete().await()
        }
        invitation.groupId
    }

    suspend fun declineInvitation(invitation: GroupInvitation): Result<Unit> = runCatching {
        firestore.collection(INVITATIONS).document(invitation.id).delete().await()
    }

    // ── Create, join, leave ─────────────────────────────────────────────

    /**
     * Creates a group with the creator as owner, in one batch (the rules
     * require it). Private groups also get an invite code.
     */
    suspend fun createGroup(
        uid: String,
        displayName: String,
        photoUrl: String?,
        name: String,
        description: String,
        private: Boolean
    ): Result<DanceGroup> = runCatching {
        val cleanName = name.trim()
        require(isValidGroupName(cleanName)) { "Group names need 3–60 characters." }
        val ref = firestore.collection(GROUPS).document()
        val now = System.currentTimeMillis()
        val code = if (private) newInviteCode() else null
        val group = DanceGroup(
            id = ref.id,
            name = cleanName,
            description = description.trim().take(500),
            visibility = if (private) DanceGroup.VISIBILITY_PRIVATE else DanceGroup.VISIBILITY_PUBLIC,
            ownerUid = uid,
            ownerName = displayName.ifBlank { "User" },
            memberCount = 1,
            inviteCode = code,
            createdAt = now
        )
        val data = mutableMapOf<String, Any>(
            "id" to group.id,
            "name" to group.name,
            "nameLower" to group.name.lowercase(),
            "description" to group.description,
            "visibility" to group.visibility,
            "ownerUid" to uid,
            "ownerName" to group.ownerName,
            "createdAt" to now
        )
        code?.let { data["inviteCode"] = it }

        val batch = firestore.batch()
        batch.set(ref, data)
        batch.set(ref.collection(MEMBERS).document(uid), memberData(uid, displayName, photoUrl, GroupMember.ROLE_OWNER, now))
        code?.let { batch.set(firestore.collection(INVITES).document(it), mapOf("groupId" to ref.id, "createdAt" to now)) }
        batch.commit().await()
        group
    }

    suspend fun joinPublicGroup(groupId: String, uid: String, displayName: String, photoUrl: String?): Result<Unit> =
        runCatching {
            groupRef(groupId).collection(MEMBERS).document(uid)
                .set(memberData(uid, displayName, photoUrl, GroupMember.ROLE_MEMBER, System.currentTimeMillis()))
                .await()
        }

    /** Joins the private (or public) group an invite code belongs to; returns its id. */
    suspend fun joinWithCode(code: String, uid: String, displayName: String, photoUrl: String?): Result<String> =
        runCatching {
            val invite = firestore.collection(INVITES).document(code).get().await()
            val groupId = invite.getString("groupId")
            require(invite.exists() && !groupId.isNullOrBlank()) { "No group has that invite code." }
            // Scanning a group's QR code again just opens it. Non-members can't
            // read a private group's member docs, so a failed read means "not yet".
            val memberRef = groupRef(groupId!!).collection(MEMBERS).document(uid)
            if (runCatching { memberRef.get().await().exists() }.getOrDefault(false)) return@runCatching groupId
            val data = memberData(uid, displayName, photoUrl, GroupMember.ROLE_MEMBER, System.currentTimeMillis()) +
                ("inviteCode" to code)
            memberRef.set(data).await()
            groupId
        }

    suspend fun leaveGroup(groupId: String, uid: String): Result<Unit> = runCatching {
        groupRef(groupId).collection(MEMBERS).document(uid).delete().await()
    }

    /** Owner only. The groupCleanup function removes posts, members, invite and videos. */
    suspend fun deleteGroup(groupId: String): Result<Unit> = runCatching {
        groupRef(groupId).delete().await()
    }

    /** Owner only. */
    /** Removes a member (owner only). They're banned too, so the invite code can't bring them back. */
    suspend fun removeMember(groupId: String, memberUid: String): Result<Unit> = runCatching {
        val batch = firestore.batch()
        batch.delete(groupRef(groupId).collection(MEMBERS).document(memberUid))
        batch.set(
            groupRef(groupId).collection(BANS).document(memberUid),
            mapOf("uid" to memberUid, "bannedAt" to System.currentTimeMillis())
        )
        batch.commit().await()
    }

    // ── Posts and replies ───────────────────────────────────────────────

    suspend fun createTextPost(groupId: String, author: Author, body: String): Result<Unit> = runCatching {
        val ref = groupRef(groupId).collection(POSTS).document()
        ref.set(postData(ref.id, author, body, GroupPostKind.TEXT)).await()
    }

    /** Shares one of the author's public dances (from the main feed) in the group. */
    suspend fun createDancePost(groupId: String, author: Author, body: String, dance: DanceLibraryModel): Result<Unit> =
        runCatching {
            val ref = groupRef(groupId).collection(POSTS).document()
            val data = postData(ref.id, author, body, GroupPostKind.DANCE) + mapOf(
                "danceId" to dance.danceId,
                "danceTitle" to dance.title,
                "videoUrl" to dance.videoUrl,
                "thumbnailUrl" to dance.thumbnailUrl.ifBlank { null }
            )
            ref.set(data).await()
        }

    /**
     * Uploads a video for this group only: compressed to 720p when that makes
     * it smaller, plus a cover thumbnail, then posts it.
     */
    suspend fun createVideoPost(
        context: Context,
        groupId: String,
        author: Author,
        body: String,
        videoUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val ref = groupRef(groupId).collection(POSTS).document()
        val videoPath = "groupVideos/${author.uid}/${ref.id}.mp4"
        val thumbPath = "groupThumbnails/${author.uid}/${ref.id}.jpg"

        onProgress(0.02f)
        val compressed: File? = runCatching {
            videoCompressor.compressVideo(videoUri, outputHeight = 720)
        }.getOrNull()
        val originalSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val upload = if (compressed != null && compressed.length() > 0 &&
            (originalSize <= 0 || compressed.length() < originalSize)
        ) Uri.fromFile(compressed) else videoUri
        onProgress(0.2f)

        val videoRef = storage.reference.child(videoPath)
        videoRef.putFileWithProgress(
            upload,
            StorageMetadata.Builder().setContentType("video/mp4").build(),
            baseProgress = 0.2f,
            maxProgress = 0.9f,
            onProgress = onProgress
        )
        val videoUrl = videoRef.downloadUrl.await().toString()
        compressed?.delete()

        val thumbnailUrl = runCatching {
            val jpeg = videoFrames.coverJpeg(videoUri, timeMs = 1_000L)
            val thumbRef = storage.reference.child(thumbPath)
            thumbRef.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
            thumbRef.downloadUrl.await().toString()
        }.getOrNull()
        onProgress(0.97f)

        val data = postData(ref.id, author, body, GroupPostKind.VIDEO) + mapOf(
            "videoUrl" to videoUrl,
            "thumbnailUrl" to thumbnailUrl,
            "storagePath" to videoPath,
            "thumbnailPath" to thumbnailUrl?.let { thumbPath }
        )
        ref.set(data).await()
        onProgress(1f)
    }

    /** Deletes a post (author or group owner), its replies, and an author's own group-only files. */
    suspend fun deletePost(post: GroupPost, requesterUid: String): Result<Unit> = runCatching {
        val ref = groupRef(post.groupId).collection(POSTS).document(post.id)
        runCatching { ref.collection(REPLIES).get().await().documents.forEach { it.reference.delete().await() } }
        ref.delete().await()
        // Storage rules let only the uploader delete their files; for posts the
        // owner removes, the files go when the group is deleted.
        if (post.uid == requesterUid) {
            listOfNotNull(post.storagePath, post.thumbnailPath).forEach { path ->
                runCatching { storage.reference.child(path).delete().await() }
            }
        }
    }

    suspend fun createReply(groupId: String, postId: String, author: Author, body: String): Result<Unit> =
        runCatching {
            val clean = body.trim().take(500)
            require(clean.isNotEmpty())
            val ref = groupRef(groupId).collection(POSTS).document(postId).collection(REPLIES).document()
            ref.set(
                mapOf(
                    "id" to ref.id,
                    "postId" to postId,
                    "uid" to author.uid,
                    "displayName" to author.displayName,
                    "photoUrl" to author.photoUrl,
                    "body" to clean,
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()
        }

    /** The signed-in user as shown on posts and replies. */
    data class Author(val uid: String, val displayName: String, val photoUrl: String?)

    // ── Mapping ─────────────────────────────────────────────────────────

    private fun newInviteCode(): String =
        (1..INVITE_CODE_LENGTH).map { INVITE_CODE_ALPHABET[random.nextInt(INVITE_CODE_ALPHABET.length)] }.joinToString("")

    private fun memberData(uid: String, displayName: String, photoUrl: String?, role: String, now: Long) =
        mapOf(
            "uid" to uid,
            "displayName" to displayName.ifBlank { "User" },
            "photoUrl" to photoUrl,
            "role" to role,
            "joinedAt" to now
        )

    private fun postData(id: String, author: Author, body: String, kind: GroupPostKind) =
        mapOf(
            "id" to id,
            "uid" to author.uid,
            "displayName" to author.displayName.ifBlank { "User" },
            "photoUrl" to author.photoUrl,
            "body" to body.trim().take(1_500),
            "kind" to kind.key,
            "createdAt" to System.currentTimeMillis()
        )

    private fun DocumentSnapshot.toGroupOrNull(): DanceGroup? {
        if (!exists()) return null
        val name = getString("name")?.takeIf { it.isNotBlank() } ?: return null
        return DanceGroup(
            id = id,
            name = name,
            description = getString("description").orEmpty(),
            visibility = getString("visibility") ?: DanceGroup.VISIBILITY_PUBLIC,
            ownerUid = getString("ownerUid").orEmpty(),
            ownerName = getString("ownerName").orEmpty(),
            memberCount = getLong("memberCount")?.toInt(),
            inviteCode = getString("inviteCode"),
            createdAt = getLong("createdAt") ?: 0L
        )
    }

    private fun DocumentSnapshot.toMember() = GroupMember(
        uid = getString("uid") ?: id,
        displayName = getString("displayName")?.takeIf { it.isNotBlank() } ?: "User",
        photoUrl = getString("photoUrl"),
        role = getString("role") ?: GroupMember.ROLE_MEMBER,
        joinedAt = getLong("joinedAt") ?: 0L
    )

    private fun DocumentSnapshot.toPostOrNull(groupId: String): GroupPost? {
        val kind = GroupPostKind.fromKey(getString("kind"))
        val body = getString("body").orEmpty()
        if (kind == GroupPostKind.TEXT && body.isBlank()) return null
        return GroupPost(
            id = getString("id") ?: id,
            groupId = groupId,
            uid = getString("uid").orEmpty(),
            displayName = getString("displayName")?.takeIf { it.isNotBlank() } ?: "User",
            photoUrl = getString("photoUrl"),
            body = body,
            kind = kind,
            danceId = getString("danceId"),
            danceTitle = getString("danceTitle"),
            videoUrl = getString("videoUrl"),
            thumbnailUrl = getString("thumbnailUrl"),
            storagePath = getString("storagePath"),
            thumbnailPath = getString("thumbnailPath"),
            createdAt = getLong("createdAt") ?: 0L
        )
    }

    private fun DocumentSnapshot.toReplyOrNull(postId: String): GroupReply? {
        val body = getString("body")?.takeIf { it.isNotBlank() } ?: return null
        return GroupReply(
            id = getString("id") ?: id,
            postId = getString("postId") ?: postId,
            uid = getString("uid").orEmpty(),
            displayName = getString("displayName")?.takeIf { it.isNotBlank() } ?: "User",
            photoUrl = getString("photoUrl"),
            body = body,
            createdAt = getLong("createdAt") ?: 0L
        )
    }

    private companion object {
        const val TAG = "GroupsRepository"
        const val GROUPS = "groups"
        const val MEMBERS = "members"
        const val BANS = "bans"
        const val INVITATIONS = "groupInvitations"
        const val POSTS = "posts"
        const val REPLIES = "replies"
        const val INVITES = "groupInvites"
        const val DISCOVER_LIMIT = 50L
        const val MEMBERS_LIMIT = 200L
        const val POSTS_LIMIT = 100L
        const val REPLIES_LIMIT = 200L
    }
}
