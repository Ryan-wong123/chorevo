package com.example.chorevo.firebase

import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.ShimmerGrid
import com.example.chorevo.videouploads.UploadsGridFilter
import com.example.chorevo.core.VideoThumbnailTile
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.GridView
import com.example.chorevo.ui.theme.ChorevoTheme
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import com.example.chorevo.analytics.AnalyticsDashboardView
import com.example.chorevo.analytics.DanceDashboardArgs
import com.example.chorevo.analytics.DanceDashboardMode
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.createCachedFeedExoPlayer
import com.example.chorevo.core.share.ShareBottomSheet
import com.example.chorevo.core.share.ShareContent
import com.example.chorevo.danceactivity.AchievementLevel
import com.example.chorevo.danceactivity.levelForPoints
import com.example.chorevo.danceactivity.nextLevelForPoints
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceSocialState
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.firebase.ViewModel.ProfileViewModel
import com.example.chorevo.videouploads.VideoUploadsTab
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
import com.example.chorevo.weeklychallenge.WeeklyChallengeSummaryCard
import com.example.chorevo.weeklychallenge.ViewModel.WeeklyChallengeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProfileTab(val title: String, val icon: ImageVector) {
    UPLOADS("Uploads", Icons.Outlined.GridView),
    PRIVATE("Private", Icons.Outlined.Lock),
    LIKED_VIDEOS("Liked", Icons.Outlined.FavoriteBorder),
    MY_DANCE("Activity", Icons.Outlined.Insights),
    OTHER_ANALYTICS("Learners", Icons.Outlined.Groups)
}

/** Displays the user profile screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    profile: UserProfile,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeeklyChallenges: () -> Unit,
    analyticsVm: AnalyticsDashboardViewModel,
    videoUploadsVm: VideoUploadsViewModel,
    profileVm: ProfileViewModel,
    danceLibraryVm: DanceLibraryListViewModel,
    weeklyChallengeVm: WeeklyChallengeViewModel
) {
    val brandTeal = ChorevoTheme.colors.accent
    val surfaceColor = MaterialTheme.colorScheme.surface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val avatarBgColor = MaterialTheme.colorScheme.surfaceVariant
    val avatarIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    var tab by remember { mutableStateOf(ProfileTab.UPLOADS) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // When set, show the fullscreen player starting at this index
    var likedPlayerStartIndex by remember { mutableStateOf<Int?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by profileVm.uiState.collectAsStateWithLifecycle()
    val weeklyChallengeState by weeklyChallengeVm.uiState.collectAsStateWithLifecycle()
    val uploadsState by videoUploadsVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(profile.uid) {
        profileVm.refresh(profile.uid)
        refreshTick++
    }

    LaunchedEffect(profile.uid) {
        weeklyChallengeVm.loadForWeek(profile.uid)
    }

    DisposableEffect(lifecycleOwner, profile.uid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profileVm.refresh(profile.uid)
                weeklyChallengeVm.loadForWeek(profile.uid)
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val name = profile.displayName?.ifBlank { "User" } ?: "User"
    val email = profile.email ?: "-"
    val currentLevel = levelForPoints(uiState.points.totalPoints)
    val nextLevel = nextLevelForPoints(uiState.points.totalPoints)

    // Fullscreen liked video player — slides over everything
    likedPlayerStartIndex?.let { startIndex ->
        LikedVideosFeed(
            uid = profile.uid,
            username = name,
            dances = uiState.likedDances,
            startIndex = startIndex,
            accentColor = brandTeal,
            danceLibraryVm = danceLibraryVm,
            onBack = { likedPlayerStartIndex = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                TextButton(onClick = onLogout) {
                    Text("Logout", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Avatar with streak ring, email and stats ──────────────────────
        item {
            val activeDays = Integer.bitCount(uiState.streak.daysMask and 0x7F)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StreakRingAvatar(
                    photoUrl = profile.photoUrl,
                    activeDays = activeDays,
                    placeholderBg = avatarBgColor,
                    placeholderTint = avatarIconColor
                )
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.isLoading) {
                    Text("Refreshing profile…", style = MaterialTheme.typography.bodySmall, color = secondaryTextColor)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryTextColor)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("profileStats"),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStat(value = uploadsState.myUploads.size.toString(), label = "Uploads")
                    ProfileStat(value = uiState.likedDances.size.toString(), label = "Liked")
                    ProfileStat(value = "$activeDays/7", label = "Streak")
                    ProfileStat(value = uiState.points.totalPoints.toString(), label = "Points")
                }
            }
        }

        item {
            WeeklyStreakCard(
                daysMask = uiState.streak.daysMask,
                totalStreakActivities = uiState.streak.totalStreakActivities,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        item {
            AchievementPanel(
                currentLevel = currentLevel,
                nextLevel = nextLevel,
                totalPoints = uiState.points.totalPoints,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        item {
            WeeklyChallengeSummaryCard(
                challenges = weeklyChallengeState.challenges,
                completedCount = weeklyChallengeState.completedCount,
                onViewAll = onOpenWeeklyChallenges,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Tab row ───────────────────────────────────────────────────────
        item {
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = Color.Transparent,
                contentColor = brandTeal,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tab.ordinal]),
                        height = 3.dp,
                        color = brandTeal
                    )
                },
                divider = { HorizontalDivider(color = dividerColor) }
            ) {
                ProfileTab.entries.forEachIndexed { index, t ->
                    val selected = tab.ordinal == index
                    Tab(
                        selected = selected,
                        onClick = { tab = t },
                        modifier = Modifier.testTag("profileTab_${t.name}"),
                        icon = {
                            Icon(
                                imageVector = t.icon,
                                contentDescription = null,
                                tint = if (selected) brandTeal else secondaryTextColor
                            )
                        },
                        text = {
                            Text(
                                text = t.title,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) brandTeal else secondaryTextColor
                            )
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // ── Tab content ───────────────────────────────────────────────────
        item {
            when (tab) {
                ProfileTab.MY_DANCE -> AnalyticsDashboardView(
                    args = DanceDashboardArgs(mode = DanceDashboardMode.MY_DANCE, myUid = profile.uid),
                    title = "My Dance Sessions",
                    vm = analyticsVm,
                    modifier = Modifier.fillMaxWidth(),
                    embeddedInParentScroll = true
                )
                ProfileTab.UPLOADS, ProfileTab.PRIVATE -> VideoUploadsTab(
                    onUploadDanceDemo = { },
                    embedded = true,
                    onStatus = { status = it },
                    vm = videoUploadsVm,
                    refreshKey = refreshTick,
                    modifier = Modifier.fillMaxWidth(),
                    embeddedInParentScroll = true,
                    gridFilter = if (tab == ProfileTab.PRIVATE) UploadsGridFilter.PRIVATE else UploadsGridFilter.PUBLIC
                )
                ProfileTab.OTHER_ANALYTICS -> AnalyticsDashboardView(
                    args = DanceDashboardArgs(
                        mode = DanceDashboardMode.OTHER_ANALYTICS,
                        myUid = profile.uid,
                        uploadedDanceId = null
                    ),
                    title = "Others Dancing to My Uploads",
                    vm = analyticsVm,
                    modifier = Modifier.fillMaxWidth(),
                    embeddedInParentScroll = true
                )
                ProfileTab.LIKED_VIDEOS -> {
                    LikedVideosGrid(
                        dances = uiState.likedDances,
                        isLoading = uiState.isLoadingLikes,
                        accentColor = brandTeal,
                        onTap = { index -> likedPlayerStartIndex = index },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ── Liked Videos grid ─────────────────────────────────────────────────────────

/**
 * 3-column thumbnail grid, matching TikTok's profile liked-videos layout.
 * Tapping a cell opens [LikedVideosFeed] starting at that index.
 */
@Composable
private fun LikedVideosGrid(
    dances: List<DanceLibraryModel>,
    isLoading: Boolean,
    accentColor: Color,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoading -> ShimmerGrid(cells = 6, modifier = modifier)

        dances.isEmpty() -> {
            AnimatedEmptyState(
                icon = Icons.Filled.Favorite,
                title = "No liked videos yet",
                message = "Heart a dance in the feed and it'll appear here",
                accent = accentColor,
                modifier = modifier
            )
        }

        else -> {
            // LazyVerticalGrid inside a LazyColumn requires a fixed height.
            // We compute it from the number of rows so everything fits.
            val columns = 3
            val cellSize = 170.dp  // thumbnail + title text
            val gap = 2.dp
            val rows = (dances.size + columns - 1) / columns
            val gridHeight = (cellSize + gap) * rows

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = modifier
                    .fillMaxWidth()
                    .height(gridHeight),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                userScrollEnabled = false   // parent LazyColumn handles scrolling
            ) {
                itemsIndexed(dances) { index, dance ->
                    VideoThumbnailTile(
                        frameTimeMs = dance.coverFrameMs,
                        videoUrl = dance.videoUrl,
                        title = dance.title,
                        onClick = { onTap(index) }
                    )
                }
            }
        }
    }
}

// ── Liked Videos fullscreen feed ─────────────────────────────────────────────

/**
 * Full-screen vertical pager for liked dances.
 * Opens at [startIndex] (the cell the user tapped).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedVideosFeed(
    uid: String,
    username: String,
    dances: List<DanceLibraryModel>,
    startIndex: Int,
    accentColor: Color,
    danceLibraryVm: DanceLibraryListViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { dances.size })

    val mainPlayer = remember { createCachedFeedExoPlayer(context = context, repeat = true) }
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

    LaunchedEffect(pagerState.currentPage, dances) {
        val item = dances.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val loadedUrl = mainPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        if (loadedUrl != item.videoUrl) {
            mainPlayer.setMediaItem(MediaItem.fromUri(item.videoUrl.toUri()))
            mainPlayer.prepare()
        }
        mainPlayer.playWhenReady = true
        mainPlayer.play()
    }

    LaunchedEffect(pagerState.currentPage, dances.size) {
        val next = dances.getOrNull(pagerState.currentPage + 1)
        if (next != null) {
            val loadedUrl = preloadPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            if (loadedUrl != next.videoUrl) {
                preloadPlayer.setMediaItem(MediaItem.fromUri(next.videoUrl.toUri()))
                preloadPlayer.prepare()
            }
        } else {
            preloadPlayer.stop()
            preloadPlayer.clearMediaItems()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("likedDancePager")
        ) { page ->
            LikedVideoPage(
                item = dances[page],
                isActive = pagerState.currentPage == page,
                exoPlayer = mainPlayer,
                uid = uid,
                accentColor = accentColor,
                danceLibraryVm = danceLibraryVm
            )
        }

        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 44.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

/** One full-screen page — video + overlay — inside the liked feed. */
@Composable
private fun LikedVideoPage(
    item: DanceLibraryModel,
    isActive: Boolean,
    exoPlayer: androidx.media3.exoplayer.ExoPlayer,
    uid: String,
    accentColor: Color,
    danceLibraryVm: DanceLibraryListViewModel
) {
    val scope = rememberCoroutineScope()

    val socialCache by danceLibraryVm.socialCache.collectAsStateWithLifecycle()
    val social = socialCache[item.danceId] ?: DanceSocialState()
    val likeCount = social.likeCount
    val isLiked = social.isLiked

    LaunchedEffect(item.danceId) { danceLibraryVm.loadSocialData(item.danceId, uid) }

    var heartBurstVisible by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }

    if (showShareSheet) {
        ShareBottomSheet(
            content = ShareContent(item.danceId, item.title, item.educatorName),
            onDismiss = { showShareSheet = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Video
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (!isLiked) danceLibraryVm.toggleLike(item.danceId, uid, isCurrentlyLiked = false)
                        heartBurstVisible = true
                        scope.launch { delay(900); heartBurstVisible = false }
                    })
                }
        ) {
            if (isActive) {
                SharedPlayerView(exoPlayer = exoPlayer, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Double-tap heart burst
        if (heartBurstVisible) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = ChorevoTheme.colors.like.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(80.dp)
            )
        }

        // Gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // Title + author
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 80.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("by ${item.educatorName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = accentColor)
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        // Right-rail: like + share
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Like
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { danceLibraryVm.toggleLike(item.danceId, uid, isCurrentlyLiked = isLiked) }) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) ChorevoTheme.colors.like else Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(if (likeCount > 0) likeCount.toString() else "Like", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            // Share
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { showShareSheet = true }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Text("Share", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

// ── Profile sub-components ────────────────────────────────────────────────────

@Composable
private fun WeeklyStreakCard(daysMask: Int, totalStreakActivities: Int, modifier: Modifier = Modifier) {
    val days = listOf("Mon", "Tues", "Wed", "Thurs", "Fri", "Sat", "Sun")
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Weekly Streak", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEachIndexed { idx, label ->
                    val done = ((daysMask shr idx) and 1) == 1
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(if (done) ChorevoTheme.colors.success else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("$totalStreakActivities Streak Activities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AchievementPanel(currentLevel: AchievementLevel, nextLevel: AchievementLevel?, totalPoints: Int, modifier: Modifier = Modifier) {
    val progress = if (nextLevel != null) {
        val range = nextLevel.pointsRequired - currentLevel.pointsRequired
        val soFar = totalPoints - currentLevel.pointsRequired
        (soFar.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    } else 1f

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Achievements", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${currentLevel.emoji} ${currentLevel.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("$totalPoints pts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                if (nextLevel != null) {
                    Text("${nextLevel.pointsRequired - totalPoints} to ${nextLevel.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                } else {
                    Text("Max Level 🏆", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = ChorevoTheme.colors.accent,
                trackColor = ChorevoTheme.colors.accent.copy(alpha = 0.2f)
            )
        }
    }
}

/** Profile photo inside a brand-gradient ring when the user practised this week. */
@Composable
private fun StreakRingAvatar(
    photoUrl: String?,
    activeDays: Int,
    placeholderBg: Color,
    placeholderTint: Color
) {
    val colors = ChorevoTheme.colors
    val ring = if (activeDays > 0) colors.brandGradient else Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outline)
    )
    Box(contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(ring)
                .padding(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(placeholderBg)
                .testTag("streakRingAvatar"),
            contentAlignment = Alignment.Center
        ) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = placeholderTint, modifier = Modifier.size(48.dp))
            }
        }
        if (activeDays > 0) {
            Row(
                modifier = Modifier
                    .offset(y = 8.dp)
                    .clip(CircleShape)
                    .background(colors.brandGradient)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = colors.onGradient,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "$activeDays",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onGradient
                )
            }
        }
    }
}

/** One number + label in the profile stats row. */
@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

