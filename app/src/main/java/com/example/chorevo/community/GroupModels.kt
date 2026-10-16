package com.example.chorevo.community

import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.UserSearchResult

/** A dance group. Public groups are listed and open to anyone; private ones need an invite code. */
data class DanceGroup(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val visibility: String = VISIBILITY_PUBLIC,
    val ownerUid: String = "",
    val ownerName: String = "",
    /** Kept by a Cloud Function; null until it has run for a new group. */
    val memberCount: Int? = null,
    /** Private groups only; readable by members so they can share it. */
    val inviteCode: String? = null,
    val createdAt: Long = 0L
) {
    val isPrivate: Boolean get() = visibility == VISIBILITY_PRIVATE

    companion object {
        const val VISIBILITY_PUBLIC = "public"
        const val VISIBILITY_PRIVATE = "private"
    }
}

data class GroupMember(
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val role: String = ROLE_MEMBER,
    val joinedAt: Long = 0L
) {
    val isOwner: Boolean get() = role == ROLE_OWNER

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_MEMBER = "member"
    }
}

/** What a group post carries besides its text. */
enum class GroupPostKind(val key: String) {
    TEXT("text"),
    /** One of the author's public dances from the main feed. */
    DANCE("dance"),
    /** A video uploaded only for this group. */
    VIDEO("video");

    companion object {
        fun fromKey(key: String?): GroupPostKind = entries.firstOrNull { it.key == key } ?: TEXT
    }
}

data class GroupPost(
    val id: String = "",
    val groupId: String = "",
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val body: String = "",
    val kind: GroupPostKind = GroupPostKind.TEXT,
    val danceId: String? = null,
    val danceTitle: String? = null,
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val storagePath: String? = null,
    val thumbnailPath: String? = null,
    val createdAt: Long = 0L
)

data class GroupReply(
    val id: String = "",
    val postId: String = "",
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val body: String = "",
    val createdAt: Long = 0L
)

/** An invitation for one user to join a group, sent by a member (`groupInvitations/{groupId}_{inviteeUid}`). */
data class GroupInvitation(
    val id: String = "",
    val groupId: String = "",
    /** Copied from the group: the invitee can't read a private group until they join. */
    val groupName: String = "",
    val inviteeUid: String = "",
    val inviteeUsername: String = "",
    val inviterUid: String = "",
    val inviterName: String = "",
    val createdAt: Long = 0L
)

fun invitationId(groupId: String, inviteeUid: String): String = "${groupId}_$inviteeUid"

/** "Invite by username" in an open group. */
data class InviteSearch(
    val query: String = "",
    val results: List<UserSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    /** Users invited from this dialog, so their button shows "Invited". */
    val invitedUids: Set<String> = emptySet(),
    val sendingUid: String? = null
)

/** The post being written in an open group. */
data class PostComposer(
    val text: String = "",
    /** A public dance of the author's to share, or null. */
    val dance: DanceLibraryModel? = null,
    /** A local video to upload for the group only, or null. */
    val videoUri: Uri? = null,
    val isPosting: Boolean = false,
    /** Upload progress 0..1 for a video post, null otherwise. */
    val progress: Float? = null
) {
    val canPost: Boolean
        get() = !isPosting && (text.isNotBlank() || dance != null || videoUri != null)
}

enum class GroupsTab { MY_GROUPS, DISCOVER }

data class GroupsUiState(
    val tab: GroupsTab = GroupsTab.MY_GROUPS,
    val myGroups: List<DanceGroup> = emptyList(),
    val isLoadingMyGroups: Boolean = true,
    val discoverQuery: String = "",
    val discoverGroups: List<DanceGroup> = emptyList(),
    val isLoadingDiscover: Boolean = false,
    val isWorking: Boolean = false,
    /** Invitations sent to the user, newest first. */
    val invitations: List<GroupInvitation> = emptyList(),
    /** Invitation being accepted or declined. */
    val answeringInvitationId: String? = null,
    val inviteSearch: InviteSearch = InviteSearch(),

    // The open group.
    val openGroupId: String? = null,
    val openGroup: DanceGroup? = null,
    val membership: GroupMember? = null,
    val members: List<GroupMember> = emptyList(),
    val posts: List<GroupPost> = emptyList(),
    val isLoadingPosts: Boolean = false,
    val composer: PostComposer = PostComposer(),
    /** The user's public dances, for "share a dance". */
    val myDances: List<DanceLibraryModel> = emptyList(),

    // The open post's replies.
    val openPostId: String? = null,
    val replies: List<GroupReply> = emptyList(),
    val replyDraft: String = "",
    val isReplying: Boolean = false,

    /** One-off message for a snackbar. */
    val message: String? = null
) {
    val myGroupIds: Set<String> get() = myGroups.map { it.id }.toSet()
    val isMember: Boolean get() = membership != null
    val isOwner: Boolean get() = membership?.isOwner == true
    val openPost: GroupPost? get() = posts.firstOrNull { it.id == openPostId }
}

/** Characters for invite codes: no 0/O, 1/I/L, so codes are easy to read out. */
const val INVITE_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
const val INVITE_CODE_LENGTH = 8

/** Normalises a typed invite code: upper case, alphabet characters only. */
fun normalizeInviteCode(raw: String): String =
    raw.uppercase().filter { it in INVITE_CODE_ALPHABET }.take(INVITE_CODE_LENGTH)

/** Group name rules shared with firestore.rules: 3–60 characters after trimming. */
fun isValidGroupName(name: String): Boolean = name.trim().length in 3..60

private val INVITE_LINK = Regex(
    """^(?:https?://(?:chorevo-dea05\.web\.app|chorevo-dea05\.firebaseapp\.com|chorevo\.app)/join/|chorevo://join/)""" +
        """([A-Za-z0-9-]+)/?(?:[?#].*)?$"""
)
private val BARE_INVITE_CODE = Regex("""^[A-Za-z0-9 -]{8,12}$""")

/**
 * The invite code in a scanned QR code or opened link, or null when the text
 * is not a Chorevo group invite. Accepts the invite link
 * (https://chorevo-dea05.web.app/join/<code>, chorevo://join/<code>) or a
 * bare code.
 */
fun inviteCodeFromText(text: String?): String? {
    val trimmed = text?.trim().orEmpty()
    val raw = INVITE_LINK.find(trimmed)?.groupValues?.get(1)
        ?: trimmed.takeIf { BARE_INVITE_CODE.matches(it) }
        ?: return null
    return normalizeInviteCode(raw).takeIf { it.length == INVITE_CODE_LENGTH && raw.count { c -> c.isLetterOrDigit() } == INVITE_CODE_LENGTH }
}
