package com.example.chorevo.dancelibrarylist

import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.LocalFloatingBarInset
import com.example.chorevo.core.LocalFloatingBarState
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.createCachedFeedExoPlayer
import com.example.chorevo.core.rememberHaptics
import com.example.chorevo.core.shimmer
import com.example.chorevo.core.share.ShareBottomSheet
import com.example.chorevo.core.share.ShareContent
import com.example.chorevo.dancelibrarylist.ViewModel.DanceSocialState
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
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
            if (state.isLoading) {
                FeedSkeleton()
            } else {
                AnimatedEmptyState(
                    icon = Icons.Filled.PlayArrow,
                    title = status ?: "No dances available.",
                    message = "New routines show up here as creators publish them.",
                    contentColor = Color.White
                )
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { items.size }
    )

    // Tuck the floating bottom bar away while the user swipes between videos.
    val floatingBar = LocalFloatingBarState.current
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { floatingBar.hidden = it }
    }
    DisposableEffect(floatingBar) {
        onDispose { floatingBar.hidden = false }
    }

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
                    CircularProgressIndicator(color = ChorevoTheme.colors.accent)
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
    val accentColor = ChorevoTheme.colors.accent
    // Keeps the title and dock above the floating bottom bar.
    val barInset = LocalFloatingBarInset.current
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
    val haptics = rememberHaptics()

    fun triggerLike() {
        haptics.tick()
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

        // ── 3. Scrim so overlay text stays readable on bright video ───────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp + barInset)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )

        // ── 4. Right rail: creator, like, comment, share ──────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 24.dp + barInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CreatorAvatar(name = item.educatorName)
            RailAction(
                icon    = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label   = if (likeCount > 0) formatCount(likeCount) else "Like",
                tint    = if (isLiked) ChorevoTheme.colors.like else Color.White,
                onClick = {
                    if (uid != null) {
                        haptics.tick()
                        vm.toggleLike(item.danceId, uid, isCurrentlyLiked = isLiked)
                    }
                },
                testTag = "likeButton"
            )
            RailAction(
                icon    = Icons.Filled.ChatBubble,
                label   = if (comments.isNotEmpty()) formatCount(comments.size) else "Comment",
                tint    = Color.White,
                onClick = { showComments = true },
                testTag = "commentButton"
            )
            RailAction(
                icon    = Icons.AutoMirrored.Filled.Reply,
                label   = "Share",
                tint    = Color.White,
                onClick = { showShareSheet = true },
                testTag = "shareButton",
                mirrorIcon = true
            )
        }

        // ── 5. Bottom-left info: creator, title, caption, chips, CTA ───────
        VideoInfo(
            item = item,
            accentColor = accentColor,
            onStartPractice = onStartPractice,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 84.dp, bottom = 24.dp + barInset)
        )

        // ── 6. Thin progress scrubber just above the bottom bar ───────────
        VideoScrubber(
            exoPlayer = exoPlayer,
            playing = isActive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (barInset - 4.dp).coerceAtLeast(0.dp))
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
                        onValueChange = { commentText = it.take(DanceLibraryRepository.MAX_COMMENT_LENGTH) },
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
                            contentColor   = ChorevoTheme.colors.onAccent
                        )
                    ) {
                        Text("Post", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Abbreviates counts the way social apps do: 999, 1.2K, 3.4M. */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f).replace(".0M", "M")
    count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000f).replace(".0K", "K")
    else -> count.toString()
}

/** Creator avatar with the brand gradient ring (initial on a dark disc). */
@Composable
private fun CreatorAvatar(name: String) {
    val colors = ChorevoTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.brandGradient)
            .padding(2.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** One right-rail action: a large icon with its count underneath. */
@Composable
private fun RailAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
    mirrorIcon: Boolean = false
) {
    val iconTint by animateColorAsState(
        targetValue   = tint,
        animationSpec = tween(200),
        label         = "railIconTint"
    )
    // Springy pop whenever the icon swaps (e.g. like ↔ unlike), skipped on first show.
    val pop = remember { Animatable(1f) }
    var iconSettled by remember { mutableStateOf(false) }
    LaunchedEffect(icon) {
        if (iconSettled) {
            pop.snapTo(1.35f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
        iconSettled = true
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = iconTint,
            modifier           = Modifier
                .size(32.dp)
                .graphicsLayer {
                    scaleX = pop.value * (if (mirrorIcon) -1f else 1f)
                    scaleY = pop.value
                }
        )
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelMedium,
            color      = Color.White
        )
    }
}

/** Creator, title, expandable caption, chips and the Practice call to action. */
@Composable
private fun VideoInfo(
    item: DanceLibraryModel,
    accentColor: Color,
    onStartPractice: () -> Unit,
    modifier: Modifier = Modifier
) {
    var captionExpanded by rememberSaveable(item.danceId) { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "by ${item.educatorName}",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = if (captionExpanded) 8 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .animateContentSize()
                    .clickable { captionExpanded = !captionExpanded }
                    .testTag("videoCaption")
            )
        }

        val chips = buildList {
            item.danceType.takeIf { it.isNotBlank() }?.let { add(it) }
            if (item.dancerCount > 1) add("${item.dancerCount} dancers")
        }
        if (chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { label -> OverlayChip(label) }
            }
        }

        Spacer(Modifier.height(4.dp))

        val colors = ChorevoTheme.colors
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(PillShape)
                .background(colors.brandGradient)
                .clickable(onClick = onStartPractice)
                .testTag("practiceButton")
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = colors.onGradient,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Practice",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onGradient
            )
        }
    }
}

/** Small translucent chip for dance type and dancer count. */
@Composable
private fun OverlayChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * Thin playback progress bar. Tap or drag to seek; it thickens while dragging.
 */
@Composable
private fun VideoScrubber(
    exoPlayer: ExoPlayer,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(playing) {
        while (playing) {
            val duration = exoPlayer.duration
            if (!dragging && duration > 0) {
                progress = (exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            }
            delay(100)
        }
    }

    fun seekTo(fraction: Float) {
        val duration = exoPlayer.duration
        if (duration > 0) exoPlayer.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    val accent = ChorevoTheme.colors.accent
    val trackHeight by animateDpAsState(if (dragging) 6.dp else 2.dp, label = "scrubberHeight")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .testTag("videoScrubber")
            .pointerInput(playing) {
                if (!playing) return@pointerInput
                detectTapGestures { offset -> seekTo(offset.x / size.width) }
            }
            .pointerInput(playing) {
                if (!playing) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { seekTo(progress); dragging = false },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        progress = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .background(Color.White.copy(alpha = 0.25f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(trackHeight)
                .background(accent)
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
            tint               = ChorevoTheme.colors.like.copy(alpha = alpha.value),
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
/** Placeholder for the feed while the first page loads: rail buttons and caption lines. */
@Composable
private fun FeedSkeleton() {
    val base = Color.White.copy(alpha = 0.08f)
    val highlight = Color.White.copy(alpha = 0.18f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("feedSkeleton")
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            repeat(4) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .shimmer(base, highlight)
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(120.dp, 220.dp, 170.dp).forEach { w ->
                Box(
                    Modifier
                        .width(w)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .shimmer(base, highlight)
                )
            }
        }
    }
}
