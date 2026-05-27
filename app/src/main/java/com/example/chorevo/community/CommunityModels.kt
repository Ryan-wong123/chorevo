package com.example.chorevo.community

data class CommunityPost(
    val id: String = "",
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val title: String = "",
    val body: String = "",
    val createdAt: Long = 0L
)

data class CommunityReply(
    val id: String = "",
    val postId: String = "",
    val uid: String = "",
    val displayName: String = "User",
    val photoUrl: String? = null,
    val body: String = "",
    val createdAt: Long = 0L
)

data class CommunityUiState(
    val posts: List<CommunityPost> = emptyList(),
    val selectedPostId: String? = null,
    val replies: List<CommunityReply> = emptyList(),
    val postTitleDraft: String = "",
    val postBodyDraft: String = "",
    val replyDraft: String = "",
    val isPosting: Boolean = false,
    val isReplying: Boolean = false,
    val errorMessage: String? = null
)
