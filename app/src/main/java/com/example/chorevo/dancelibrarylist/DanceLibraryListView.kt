package com.example.chorevo.dancelibrarylist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.createCachedFeedExoPlayer
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel

/** Handles dance library list view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DanceLibraryListView(
    onPickDance: (DanceLibraryModel) -> Unit,
    onBack: () -> Unit,
    vm: DanceLibraryListViewModel,
    uid: String?
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val upload by vm.uploadState.collectAsStateWithLifecycle()

    val items = state.items
    val status = state.message

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
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(status ?: "Loading…")
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { items.size }
    )

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

    // Play current page on main player
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

    // Preload next page silently
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
                onStartPractice = {
                    onPickDance(items[page])
                }
            )
        }

        if (upload.isWorking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/** Handles tik tok video page. */
@Composable
private fun TikTokVideoPage(
    item: DanceLibraryModel,
    isActive: Boolean,
    exoPlayer: ExoPlayer,
    onStartPractice: () -> Unit
) {
    val cardColor = MaterialTheme.colorScheme.surface
    val primaryTextColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val bodyTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    Box(modifier = Modifier.fillMaxSize()) {

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

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryTextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "by ${item.educatorName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor
                    )

                    Spacer(Modifier.height(10.dp))

                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = bodyTextColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onStartPractice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("practiceButton"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00B8C8),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Practice",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
