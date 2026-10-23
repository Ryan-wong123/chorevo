package com.example.chorevo.community

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import com.example.chorevo.community.viewmodel.GroupsViewModel
import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.ShimmerList
import com.example.chorevo.core.rememberFeedPlayerFactory
import com.example.chorevo.core.isTrustedMediaUrl
import com.example.chorevo.core.share.ShareLinks
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.UserSearchResult
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.videouploads.VideoCaptureFileProvider

/**
 * One open group: its header, posts (text, shared dances and group-only
 * videos), a composer for members, and replies in a bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    state: GroupsUiState,
    myUid: String,
    vm: GroupsViewModel,
    snackbar: SnackbarHostState,
    onOpenDance: (String) -> Unit
) {
    val context = LocalContext.current
    val group = state.openGroup
    var menuOpen by remember { mutableStateOf(false) }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showDancePicker by rememberSaveable { mutableStateOf(false) }
    var playingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showInvite by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        if (state.openPostId != null) vm.closePost() else vm.closeGroup()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeGroup, modifier = Modifier.testTag("groupBackButton")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        group?.name ?: "Group",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (group != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("groupMenuButton")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Group options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Members") },
                                    onClick = { menuOpen = false; showMembers = true }
                                )
                                if (state.isMember) {
                                    DropdownMenuItem(
                                        text = { Text("Invite by username") },
                                        onClick = { menuOpen = false; vm.resetInviteSearch(); showInvite = true },
                                        modifier = Modifier.testTag("inviteByUsernameItem")
                                    )
                                }
                                if (group.isPrivate && state.isMember && group.inviteCode != null) {
                                    DropdownMenuItem(
                                        text = { Text("Show invite QR code") },
                                        onClick = { menuOpen = false; showQr = true },
                                        modifier = Modifier.testTag("showQrItem")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share invite") },
                                        onClick = { menuOpen = false; shareInvite(context, group) }
                                    )
                                }
                                if (state.isMember && !state.isOwner) {
                                    DropdownMenuItem(
                                        text = { Text("Leave group") },
                                        onClick = { menuOpen = false; confirmLeave = true },
                                        modifier = Modifier.testTag("leaveGroupItem")
                                    )
                                }
                                if (state.isOwner) {
                                    DropdownMenuItem(
                                        text = { Text("Delete group", color = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; confirmDelete = true },
                                        modifier = Modifier.testTag("deleteGroupItem")
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (state.isMember) {
                Composer(
                    composer = state.composer,
                    hasDances = state.myDances.isNotEmpty(),
                    onTextChange = vm::setComposerText,
                    onPickDance = { showDancePicker = true },
                    onVideo = vm::attachVideo,
                    onClearAttachment = vm::clearAttachment,
                    onPost = { vm.submitPost(context) }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("groupPostList"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (group != null) {
                item(key = "header") {
                    GroupHeader(group = group, isMember = state.isMember, context = context, onShowQr = { showQr = true })
                }
            }
            if (group != null && !state.isMember && !group.isPrivate) {
                item(key = "join") {
                    JoinBanner(working = state.isWorking, onJoin = vm::joinOpenGroup)
                }
            }
            when {
                state.isLoadingPosts -> item(key = "loading") { ShimmerList(rows = 3) }
                state.posts.isEmpty() -> item(key = "empty") {
                    AnimatedEmptyState(
                        icon = Icons.Filled.Forum,
                        title = "No posts yet",
                        message = if (state.isMember) {
                            "Start the conversation: share a dance, a video or a question."
                        } else {
                            "Join the group to post and reply."
                        }
                    )
                }
                else -> items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        canDelete = post.uid == myUid || state.isOwner,
                        onOpenDance = onOpenDance,
                        onPlay = { playingUrl = it },
                        onReplies = { vm.openPost(post.id) },
                        onDelete = { vm.deletePost(post) }
                    )
                }
            }
        }
    }

    state.openPost?.let { post ->
        RepliesSheet(
            post = post,
            replies = state.replies,
            draft = state.replyDraft,
            isMember = state.isMember,
            isReplying = state.isReplying,
            onDraftChange = vm::setReplyDraft,
            onSend = vm::sendReply,
            onDismiss = vm::closePost
        )
    }

    if (showMembers) {
        MembersDialog(
            members = state.members,
            myUid = myUid,
            canRemove = state.isOwner,
            onRemove = vm::removeMember,
            onDismiss = { showMembers = false }
        )
    }
    if (showDancePicker) {
        DancePickerDialog(
            dances = state.myDances,
            onPick = { vm.attachDance(it); showDancePicker = false },
            onDismiss = { showDancePicker = false }
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave this group?",
            text = if (group?.isPrivate == true) "You'll need an invite code to join again." else "You can join again any time.",
            confirm = "Leave",
            onConfirm = { confirmLeave = false; vm.leaveOpenGroup() },
            onDismiss = { confirmLeave = false }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this group?",
            text = "All posts, replies and group videos are removed for everyone. This can't be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; vm.deleteOpenGroup() },
            onDismiss = { confirmDelete = false }
        )
    }
    playingUrl?.let { url -> VideoDialog(url = url, onDismiss = { playingUrl = null }) }
    if (showInvite && group != null && state.isMember) {
        InviteByUsernameDialog(
            search = state.inviteSearch,
            memberUids = state.members.map { it.uid }.toSet(),
            onQueryChange = vm::setInviteQuery,
            onInvite = vm::sendInvitation,
            onDismiss = { showInvite = false; vm.resetInviteSearch() }
        )
    }
    if (showQr && group != null && group.inviteCode != null && state.isMember) {
        InviteQrDialog(group = group, onShare = { shareInvite(context, group) }, onDismiss = { showQr = false })
    }
}

@Composable
private fun GroupHeader(group: DanceGroup, isMember: Boolean, context: Context, onShowQr: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(group.name, size = 52)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (group.isPrivate) Icons.Filled.Lock else Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            (if (group.isPrivate) "Private" else "Public") + " · " + memberCountLabel(group.memberCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (group.description.isNotBlank()) {
                Text(group.description, style = MaterialTheme.typography.bodyMedium)
            }
            val code = group.inviteCode
            if (group.isPrivate && isMember && code != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(start = 16.dp, end = 4.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Invite code", style = MaterialTheme.typography.labelSmall)
                        Text(
                            code,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("groupInviteCode")
                        )
                    }
                    IconButton(onClick = onShowQr, modifier = Modifier.testTag("showQrButton")) {
                        Icon(Icons.Filled.QrCode2, contentDescription = "Show invite QR code")
                    }
                    IconButton(onClick = { copyToClipboard(context, code) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy invite code")
                    }
                    IconButton(onClick = { shareInvite(context, group) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share invite")
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinBanner(working: Boolean, onJoin: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = ChorevoTheme.colors.accent.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Join to post your dances and reply to others.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onJoin,
                enabled = !working,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChorevoTheme.colors.accent,
                    contentColor = ChorevoTheme.colors.onAccent
                ),
                modifier = Modifier.testTag("joinGroupButton")
            ) { Text("Join group") }
        }
    }
}

@Composable
private fun PostCard(
    post: GroupPost,
    canDelete: Boolean,
    onOpenDance: (String) -> Unit,
    onPlay: (String) -> Unit,
    onReplies: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("groupPost_${post.id}")
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthorRow(post.displayName, post.photoUrl, post.createdAt)
            if (post.body.isNotBlank()) {
                Text(post.body, style = MaterialTheme.typography.bodyMedium)
            }
            when (post.kind) {
                GroupPostKind.DANCE -> {
                    val danceId = post.danceId
                    Thumbnail(
                        url = post.thumbnailUrl,
                        label = post.danceTitle ?: "Dance",
                        icon = Icons.Filled.MusicNote,
                        onClick = { if (danceId != null) onOpenDance(danceId) }
                    )
                    if (danceId != null) {
                        AssistChip(
                            onClick = { onOpenDance(danceId) },
                            label = { Text("Open dance") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            modifier = Modifier.testTag("openDance_${post.id}")
                        )
                    }
                }
                GroupPostKind.VIDEO -> {
                    val url = post.videoUrl?.takeIf(::isTrustedMediaUrl)
                    Thumbnail(
                        url = post.thumbnailUrl,
                        label = null,
                        icon = Icons.Filled.PlayArrow,
                        onClick = { if (url != null) onPlay(url) }
                    )
                }
                GroupPostKind.TEXT -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReplies, modifier = Modifier.testTag("replies_${post.id}")) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Replies")
                }
                Spacer(Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("deletePost_${post.id}")) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete post")
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this post?",
            text = "Its replies are deleted too.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun AuthorRow(name: String, photoUrl: String?, createdAt: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
            )
        } else {
            GroupAvatar(name, size = 32)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (createdAt > 0) {
                Text(
                    relativeTime(createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(
    url: String?,
    label: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color.Black,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isTrustedMediaUrl(url)) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            if (label != null) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun Composer(
    composer: PostComposer,
    hasDances: Boolean,
    onTextChange: (String) -> Unit,
    onPickDance: () -> Unit,
    onVideo: (Uri) -> Unit,
    onClearAttachment: () -> Unit,
    onPost: () -> Unit
) {
    val context = LocalContext.current
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showVideoChoice by remember { mutableStateOf(false) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideo(uri)
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val uri = captureUri
        if (saved && uri != null) onVideo(uri)
    }
    val launchRecording = {
        VideoCaptureFileProvider.createVideoCaptureUri(context)?.let {
            captureUri = it
            recordVideo.launch(it)
        }
    }
    // The app declares CAMERA, so the camera app intent needs that permission first.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchRecording()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (composer.isPosting) {
                val p = composer.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(6.dp))
            }
            val attachment = composer.dance?.title?.let { "Dance: $it" }
                ?: composer.videoUri?.let { "Video attached" }
            if (attachment != null) {
                InputChip(
                    selected = true,
                    onClick = onClearAttachment,
                    enabled = !composer.isPosting,
                    label = { Text(attachment, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove attachment") },
                    modifier = Modifier.testTag("composerAttachment")
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPickDance,
                    enabled = hasDances && !composer.isPosting,
                    modifier = Modifier.testTag("attachDanceButton")
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = "Share one of your dances")
                }
                Box {
                    IconButton(
                        onClick = { showVideoChoice = true },
                        enabled = !composer.isPosting,
                        modifier = Modifier.testTag("attachVideoButton")
                    ) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Attach a video")
                    }
                    DropdownMenu(expanded = showVideoChoice, onDismissRequest = { showVideoChoice = false }) {
                        DropdownMenuItem(
                            text = { Text("Choose from gallery") },
                            leadingIcon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) },
                            onClick = { showVideoChoice = false; pickVideo.launch("video/*") }
                        )
                        DropdownMenuItem(
                            text = { Text("Record video") },
                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                            onClick = {
                                showVideoChoice = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    launchRecording()
                                } else {
                                    cameraPermission.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = composer.text,
                    onValueChange = onTextChange,
                    placeholder = { Text("Write something…") },
                    enabled = !composer.isPosting,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("groupComposerField")
                )
                IconButton(
                    onClick = onPost,
                    enabled = composer.canPost,
                    modifier = Modifier.testTag("postButton")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post",
                        tint = if (composer.canPost) ChorevoTheme.colors.accent else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepliesSheet(
    post: GroupPost,
    replies: List<GroupReply>,
    draft: String,
    isMember: Boolean,
    isReplying: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("repliesSheet")
        ) {
            AuthorRow(post.displayName, post.photoUrl, post.createdAt)
            if (post.body.isNotBlank()) {
                Text(post.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (replies.isEmpty()) {
                    item {
                        Text(
                            "No replies yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(replies, key = { it.id }) { reply ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AuthorRow(reply.displayName, reply.photoUrl, reply.createdAt)
                        Text(reply.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 42.dp))
                    }
                }
            }
            if (isMember) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = { Text("Reply…") },
                        maxLines = 3,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("replyField")
                    )
                    IconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && !isReplying,
                        modifier = Modifier.testTag("sendReplyButton")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply")
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersDialog(
    members: List<GroupMember>,
    myUid: String,
    canRemove: Boolean,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Members (${members.size})") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members, key = { it.uid }) { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AuthorRow(m.displayName, m.photoUrl, 0L) }
                        if (m.isOwner) {
                            Text("Owner", style = MaterialTheme.typography.labelMedium, color = ChorevoTheme.colors.accent)
                        } else if (canRemove && m.uid != myUid) {
                            TextButton(onClick = { onRemove(m.uid) }, modifier = Modifier.testTag("removeMember_${m.uid}")) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DancePickerDialog(
    dances: List<DanceLibraryModel>,
    onPick: (DanceLibraryModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share one of your dances") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dances, key = { it.danceId }) { dance ->
                    Surface(
                        onClick = { onPick(dance) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pickDance_${dance.danceId}")
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = dance.thumbnailUrl.ifBlank { null },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(Color.Black)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(dance.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (dance.danceType.isNotBlank()) {
                                    Text(dance.danceType, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmDialogButton")) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Find people by username and send them an invitation to this group. */
@Composable
private fun InviteByUsernameDialog(
    search: InviteSearch,
    memberUids: Set<String>,
    onQueryChange: (String) -> Unit,
    onInvite: (UserSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite by username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    prefix = { Text("@") },
                    placeholder = { Text("username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    trailingIcon = {
                        if (search.isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inviteUsernameField")
                )
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (search.query.isNotBlank() && !search.isSearching && search.results.isEmpty()) {
                        item {
                            Text(
                                "No one with that username.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(search.results, key = { it.uid }) { user ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("inviteResult_${user.username}")
                        ) {
                            Box(Modifier.weight(1f)) { AuthorRow(user.displayName, user.photoUrl, 0L) }
                            Text(
                                "@${user.username}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            when {
                                user.uid in memberUids -> Text("Member", style = MaterialTheme.typography.labelMedium)
                                user.uid in search.invitedUids -> Text(
                                    "Invited",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChorevoTheme.colors.accent
                                )
                                else -> TextButton(
                                    onClick = { onInvite(user) },
                                    enabled = search.sendingUid == null,
                                    modifier = Modifier.testTag("sendInvite_${user.username}")
                                ) { Text(if (search.sendingUid == user.uid) "…" else "Invite") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** The group's invite as a QR code, for a new member to scan with the app or the phone camera. */
@Composable
private fun InviteQrDialog(group: DanceGroup, onShare: () -> Unit, onDismiss: () -> Unit) {
    val code = group.inviteCode ?: return
    val qr = remember(code) { InviteQr.bitmap(ShareLinks.groupInviteLink(code)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join ${group.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Scan with Chorevo (Groups → scan icon) or the phone camera.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Image(
                    bitmap = qr,
                    contentDescription = "Invite QR code for ${group.name}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .testTag("inviteQrImage")
                )
                Text(code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        }
    )
}

@Composable
private fun VideoDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val feedPlayerFactory = rememberFeedPlayerFactory()
    val player = remember(url) {
        feedPlayerFactory.create(repeat = true).apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            SharedPlayerView(player, modifier = Modifier.fillMaxSize(), useController = true)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close video", tint = Color.White)
            }
        }
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun copyToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("Chorevo invite code", code).apply {
        // Android 13+ hides sensitive clips in the clipboard preview.
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
}

private fun shareInvite(context: Context, group: DanceGroup) {
    val code = group.inviteCode ?: return
    val text = "Join my dance group \"${group.name}\" on Chorevo: ${ShareLinks.groupInviteLink(code)}\n" +
        "Or open Groups, tap the key icon and enter code $code."
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share invite"))
}
