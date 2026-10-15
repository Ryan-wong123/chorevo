package com.example.chorevo.community

import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.chorevo.community.ViewModel.CommunityViewModel
import com.example.chorevo.firebase.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    profile: UserProfile,
    vm: CommunityViewModel
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val brandTeal = ChorevoTheme.colors.accent
    val displayName = profile.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: "User"
    val selectedPost = state.selectedPostId?.let { id -> state.posts.firstOrNull { it.id == id } }
    var isCreatingPost by rememberSaveable { mutableStateOf(false) }

    // System back leaves the post editor or post detail before leaving the forum.
    BackHandler(enabled = isCreatingPost || selectedPost != null) {
        if (isCreatingPost) {
            isCreatingPost = false
        } else {
            state.selectedPostId?.let(vm::selectPost)
        }
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.clearError()
    }

    Scaffold(
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (isCreatingPost || selectedPost != null) {
                        IconButton(
                            onClick = {
                                if (isCreatingPost) {
                                    isCreatingPost = false
                                } else {
                                    state.selectedPostId?.let(vm::selectPost)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = when {
                            isCreatingPost -> "Create Post"
                            selectedPost != null -> "Post"
                            else -> "Community"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { pad ->
        when {
            isCreatingPost -> {
                CreatePostPage(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .background(colors.surface)
                        .padding(16.dp),
                    title = state.postTitleDraft,
                    body = state.postBodyDraft,
                    isPosting = state.isPosting,
                    brandTeal = brandTeal,
                    onTitleChanged = vm::setPostTitleDraft,
                    onBodyChanged = vm::setPostBodyDraft,
                    onPublish = {
                        vm.createPost(
                            uid = profile.uid,
                            displayName = displayName,
                            photoUrl = profile.photoUrl,
                            onCreated = { isCreatingPost = false }
                        )
                    }
                )
            }

            selectedPost != null -> {
                PostDetailPage(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .background(colors.surface)
                        .padding(16.dp),
                    post = selectedPost,
                    replies = state.replies,
                    replyDraft = state.replyDraft,
                    isReplying = state.isReplying,
                    isMine = selectedPost.uid == profile.uid,
                    brandTeal = brandTeal,
                    onReplyChanged = vm::setReplyDraft,
                    onReply = {
                        vm.createReply(
                            uid = profile.uid,
                            displayName = displayName,
                            photoUrl = profile.photoUrl
                        )
                    }
                )
            }

            else -> {
                CommunityFeedPage(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .background(colors.surface)
                        .padding(16.dp),
                    posts = state.posts,
                    myUid = profile.uid,
                    brandTeal = brandTeal,
                    onCreatePost = { isCreatingPost = true },
                    onOpenPost = vm::selectPost
                )
            }
        }
    }
}

@Composable
private fun CommunityFeedPage(
    modifier: Modifier = Modifier,
    posts: List<CommunityPost>,
    myUid: String,
    brandTeal: Color,
    onCreatePost: () -> Unit,
    onOpenPost: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier.testTag("communityPostList"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CreatePostEntryCard(
                brandTeal = brandTeal,
                onCreatePost = onCreatePost
            )
        }

        if (posts.isEmpty()) {
            item { EmptyCommunityState() }
        } else {
            items(
                items = posts,
                key = { it.id }
            ) { post ->
                CommunityPostCard(
                    post = post,
                    isMine = post.uid == myUid,
                    brandTeal = brandTeal,
                    onOpenPost = { onOpenPost(post.id) }
                )
            }
        }
    }
}

@Composable
private fun CreatePostEntryCard(
    brandTeal: Color,
    onCreatePost: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Community Posts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Start a topic, question, or practice update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Button(
                onClick = onCreatePost,
                colors = ButtonDefaults.buttonColors(
                    containerColor = brandTeal,
                    contentColor = ChorevoTheme.colors.onAccent
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("Create Post")
            }
        }
    }
}

@Composable
private fun CreatePostPage(
    modifier: Modifier = Modifier,
    title: String,
    body: String,
    isPosting: Boolean,
    brandTeal: Color,
    onTitleChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onPublish: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "New Discussion",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("communityPostTitle"),
                    value = title,
                    onValueChange = onTitleChanged,
                    placeholder = { Text("Post title") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("communityPostBody"),
                    value = body,
                    onValueChange = onBodyChanged,
                    placeholder = { Text("Share a question, tip, progress update, or idea") },
                    minLines = 8,
                    maxLines = 12
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${body.length}/1500",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onPublish,
                        enabled = title.isNotBlank() && body.isNotBlank() && !isPosting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brandTeal,
                            contentColor = ChorevoTheme.colors.onAccent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (isPosting) "Posting" else "Publish")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCommunityState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No posts yet. Start the first dance discussion.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

@Composable
private fun CommunityPostCard(
    post: CommunityPost,
    isMine: Boolean,
    brandTeal: Color,
    onOpenPost: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPost),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PostAuthorRow(
                displayName = post.displayName,
                photoUrl = post.photoUrl,
                subtitle = if (isMine) "Your post" else "Community post",
                brandTeal = brandTeal
            )

            Text(
                text = post.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = post.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Open discussion",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = brandTeal
            )
        }
    }
}

@Composable
private fun PostDetailPage(
    modifier: Modifier = Modifier,
    post: CommunityPost,
    replies: List<CommunityReply>,
    replyDraft: String,
    isReplying: Boolean,
    isMine: Boolean,
    brandTeal: Color,
    onReplyChanged: (String) -> Unit,
    onReply: () -> Unit
) {
    LazyColumn(
        modifier = modifier.testTag("communityPostDetail"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PostAuthorRow(
                        displayName = post.displayName,
                        photoUrl = post.photoUrl,
                        subtitle = if (isMine) "Your post" else "Community post",
                        brandTeal = brandTeal
                    )
                    Text(
                        text = post.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = post.body,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Text(
                text = "Replies (${replies.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (replies.isEmpty()) {
            item {
                Text(
                    text = "No replies yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        } else {
            items(
                items = replies,
                key = { it.id }
            ) { reply ->
                ReplyCard(reply = reply, brandTeal = brandTeal)
            }
        }

        item {
            ReplyComposer(
                replyDraft = replyDraft,
                isReplying = isReplying,
                brandTeal = brandTeal,
                onReplyChanged = onReplyChanged,
                onReply = onReply
            )
        }
    }
}

@Composable
private fun ReplyComposer(
    replyDraft: String,
    isReplying: Boolean,
    brandTeal: Color,
    onReplyChanged: (String) -> Unit,
    onReply: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("communityReplyComposer"),
                value = replyDraft,
                onValueChange = onReplyChanged,
                placeholder = { Text("Reply to this post") },
                minLines = 2,
                maxLines = 5
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${replyDraft.length}/500",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onReply,
                    enabled = replyDraft.isNotBlank() && !isReplying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = ChorevoTheme.colors.onAccent
                    )
                ) {
                    Text(if (isReplying) "Replying" else "Reply")
                }
            }
        }
    }
}

@Composable
private fun ReplyCard(
    reply: CommunityReply,
    brandTeal: Color
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PostAuthorRow(
                displayName = reply.displayName,
                photoUrl = reply.photoUrl,
                subtitle = "Reply",
                brandTeal = brandTeal
            )
            HorizontalDivider()
            Text(
                text = reply.body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PostAuthorRow(
    displayName: String,
    photoUrl: String?,
    subtitle: String,
    brandTeal: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommunityAvatar(
            name = displayName,
            photoUrl = photoUrl,
            tint = brandTeal
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun CommunityAvatar(
    name: String,
    photoUrl: String?,
    tint: Color
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "U" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
    }
}
