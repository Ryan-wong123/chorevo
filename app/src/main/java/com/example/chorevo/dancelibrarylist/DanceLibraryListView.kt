package com.example.chorevo.dancelibrarylist

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.createCachedFeedExoPlayer
import com.example.chorevo.core.share.ShareBottomSheet
import com.example.chorevo.core.share.ShareContent
import com.example.chorevo.dancelibrarylist.ViewModel.DanceSocialState
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Handles dance library list view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DanceLibraryListView(
    onPickDance: (DanceLibraryModel) -> Unit,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    vm: DanceLibraryListViewModel,
    uid: String?,
    username: String = "User",
    pinnedDance: DanceLibraryModel? = null
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val upload by vm.uploadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // A dance opened from a share link plays first, followed by the usual feed.
    val items = remember(state.items, pinnedDance) {
        if (pinnedDance == null) state.items
        else listOf(pinnedDance) + state.items.filter { it.danceId != pinnedDance.danceId }
    }
    val status = state.message
    var openingDanceId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uid) {
        if (uid != null) vm.refresh()
    }

    LaunchedEffect(upload.message) {
        val msg = upload.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearUploadMessage()
    }

    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(status ?: "Loading...", color = Color.White)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { items.size }
    )

    // A link tapped while the feed is already open jumps back to the shared video.
    LaunchedEffect(pinnedDance?.danceId) {
        if (pinnedDance != null) pagerState.scrollToPage(0)
    }

    val mainPlayer = remember {
        createCachedFeedExoPlayer(context = context, repeat = true)
    }

    val preloadPlayer = remember {
        createCachedFeedExoPlayer(context = context, repeat = false).apply {
            volume = 0f
            playWhenReady = false
        }
    }

    DisposableEffect(mainPlayer, preloadPlayer) {
        onDispose {
            mainPlayer.release()
            preloadPlayer.release()
        }
    }

    LaunchedEffect(pagerState.currentPage, items) {
        val currentItem = items.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val currentUrl = currentItem.videoUrl
        val currentLoadedUrl =
            mainPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentLoadedUrl != currentUrl) {
            mainPlayer.setMediaItem(MediaItem.fromUri(currentUrl.toUri()))
            mainPlayer.prepare()
        }
        mainPlayer.playWhenReady = true
        mainPlayer.play()
    }

    LaunchedEffect(pagerState.currentPage, items.size) {
        val nextIndex = pagerState.currentPage + 1
        val nextItem = items.getOrNull(nextIndex)
        if (nextItem != null) {
            val nextUrl = nextItem.videoUrl
            val preloadLoadedUrl =
                preloadPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            if (preloadLoadedUrl != nextUrl) {
                preloadPlayer.setMediaItem(MediaItem.fromUri(nextUrl.toUri()))
                preloadPlayer.prepare()
            }
        } else {
            preloadPlayer.stop()
            preloadPlayer.clearMediaItems()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        )

        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("dancePager")
        ) { page ->
            TikTokVideoPage(
                item = items[page],
                isActive = pagerState.currentPage == page,
                exoPlayer = mainPlayer,
                uid = uid,
                username = username,
                vm = vm,
                onStartPractice = {
                    val selectedItem = items[page]
                    if (openingDanceId != null) return@TikTokVideoPage
                    openingDanceId = selectedItem.danceId
                    scope.launch {
                        vm.preparePracticeItem(selectedItem).fold(
                            onSuccess = { prepared -> onPickDance(prepared) },
                            onFailure = { error ->
                                snackbarHostState.showSnackbar(
                                    error.message ?: "Unable to load the dance reference template"
                                )
                            }
                        )
                        openingDanceId = null
                    }
                }
            )
        }

        if (upload.isWorking || openingDanceId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF00B8C8))
                    Text(
                        text = if (openingDanceId != null) "Loading reference template..." else "Submitting result...",
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** Full-screen page with a floating bottom dock instead of a right-rail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TikTokVideoPage(
    item: DanceLibraryModel,
    isActive: Boolean,
    exoPlayer: ExoPlayer,
    uid: String?,
    username: String,
    vm: DanceLibraryListViewModel,
    onStartPractice: () -> Unit
) {
    val accentColor = Color(0xFF00B8C8)
    val scope = rememberCoroutineScope()

    val socialCache by vm.socialCache.collectAsStateWithLifecycle()
    val social = socialCache[item.danceId] ?: DanceSocialState()
    val likeCount  = social.likeCount
    val isLiked    = social.isLiked
    val comments   = social.comments

    var showComments by rememberSaveable { mutableStateOf(false) }
    var commentText  by remember { mutableStateOf("") }
    var showShareSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(item.danceId) {
        if (uid != null) {
            vm.loadSocialData(item.danceId, uid)
        }
    }

    var heartBurstOffset by remember { mutableStateOf<Offset?>(null) }

    fun triggerLike() {
        if (!isLiked && uid != null) {
            vm.toggleLike(item.danceId, uid, isCurrentlyLiked = false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── 1. Full-screen video with double-tap gesture ──────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            triggerLike()
                            heartBurstOffset = offset
                            scope.launch {
                                delay(900)
                                heartBurstOffset = null
                            }
                        }
                    )
                }
        ) {
            if (isActive) {
                SharedPlayerView(
                    exoPlayer = exoPlayer,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }

        // ── 2. Double-tap heart burst animation ───────────────────────────
        heartBurstOffset?.let { offset ->
            HeartBurst(offset = offset)
        }

        // ── 3. Title + author overlay (above dock) ────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 172.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "by ${item.educatorName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = "· ${item.description}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── 4. Scrim gradient behind dock + title area ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )

        // ── 5. Floating Practice Dock ─────────────────────────────────────
        PracticeDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            likeCount    = likeCount,
            isLiked      = isLiked,
            commentCount = comments.size,
            accentColor  = accentColor,
            onLike = {
                if (uid != null) {
                    vm.toggleLike(item.danceId, uid, isCurrentlyLiked = isLiked)
                }
            },
            onComment = { showComments = true },
            onShare = { showShareSheet = true },
            onStartPractice = onStartPractice
        )
    }

    if (showShareSheet) {
        ShareBottomSheet(
            content = ShareContent(item.danceId, item.title, item.educatorName),
            onDismiss = { showShareSheet = false }
        )
    }

    // ── 6. Comments bottom sheet ──────────────────────────────────────────
    if (showComments) {
        ModalBottomSheet(
            onDismissRequest = { showComments = false },
            containerColor   = MaterialTheme.colorScheme.surface,
            shape            = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation   = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text  = if (comments.isEmpty()) "Comments" else "Comments (${comments.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                if (comments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text  = "No comments yet — be the first!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(comments) { comment ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(accentColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text  = comment.username.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accentColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                                    Text(
                                        text  = "@${comment.username}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentColor,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text  = comment.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        if (social.hasMoreComments) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (social.isLoadingMoreComments) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = accentColor
                                        )
                                    } else {
                                        TextButton(onClick = { vm.loadMoreComments(item.danceId) }) {
                                            Text("Load more comments", color = accentColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value         = commentText,
                        onValueChange = { commentText = it },
                        placeholder   = { Text("Add a comment...") },
                        modifier      = Modifier.weight(1f),
                        shape         = RoundedCornerShape(14.dp),
                        singleLine    = true
                    )
                    Button(
                        onClick = {
                            val trimmed = commentText.trim()
                            if (trimmed.isNotEmpty() && uid != null) {
                                commentText = ""
                                vm.postComment(item.danceId, uid, username, trimmed)
                            }
                        },
                        shape  = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor   = Color.White
                        )
                    ) {
                        Text("Post", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * The floating bottom dock — social row on top, CTA row below.
 *
 * Layout:
 *   ┌─────────────────────────────────────┐
 *   │  ❤️ 120    💬 32    ↗ Share         │
 *   │─────────────────────────────────────│
 *   │         [ Practice ]                │
 *   └─────────────────────────────────────┘
 */
@Composable
private fun PracticeDock(
    modifier: Modifier = Modifier,
    likeCount: Int,
    isLiked: Boolean,
    commentCount: Int,
    accentColor: Color,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
    onStartPractice: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Social actions row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            DockAction(
                icon    = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label   = if (likeCount > 0) likeCount.toString() else "Like",
                tint    = if (isLiked) Color(0xFFFF4466) else Color.White,
                onClick = onLike,
                testTag = "likeButton"
            )
            DockAction(
                icon    = Icons.Outlined.ChatBubbleOutline,
                label   = if (commentCount > 0) commentCount.toString() else "Comment",
                tint    = Color.White,
                onClick = onComment,
                testTag = "commentButton"
            )
            Spacer(Modifier.weight(1f))
            DockAction(
                icon    = Icons.Outlined.Share,
                label   = "Share",
                tint    = Color.White,
                onClick = onShare,
                testTag = "shareButton"
            )
        }

        // CTA row
        Button(
            onClick  = onStartPractice,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("practiceButton"),
            shape  = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor   = Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text          = "Practice",
                style         = MaterialTheme.typography.titleSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/** Inline social action chip: icon + label side-by-side. */
@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val iconTint by animateColorAsState(
        targetValue   = tint,
        animationSpec = tween(200),
        label         = "dockIconTint"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = iconTint,
            modifier           = Modifier.size(22.dp)
        )
        Text(
            text       = label,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White.copy(alpha = 0.9f)
        )
    }
}

/**
 * Animates a heart icon upward from the double-tap position, fading out.
 */
@Composable
private fun HeartBurst(offset: Offset) {
    val alpha   = remember { Animatable(0f) }
    val yOffset = remember { Animatable(0f) }
    val scale   = remember { Animatable(0.5f) }

    LaunchedEffect(offset) {
        launch {
            alpha.animateTo(1f, animationSpec = tween(150))
            delay(400)
            alpha.animateTo(0f, animationSpec = tween(350))
        }
        launch {
            yOffset.animateTo(-120f, animationSpec = tween(900, easing = EaseOut))
        }
        launch {
            scale.animateTo(1.4f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector        = Icons.Filled.Favorite,
            contentDescription = null,
            tint               = Color(0xFFFF4466).copy(alpha = alpha.value),
            modifier           = Modifier
                .size(72.dp)
                .offset {
                    IntOffset(
                        x = (offset.x - 36.dp.toPx()).roundToInt(),
                        y = (offset.y - 36.dp.toPx() + yOffset.value).roundToInt()
                    )
                }
                .scale(scale.value)
        )
    }
}