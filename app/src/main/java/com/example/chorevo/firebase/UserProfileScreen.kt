package com.example.chorevo.firebase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.chorevo.analytics.AnalyticsDashboardView
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import com.example.chorevo.analytics.DanceDashboardArgs
import com.example.chorevo.analytics.DanceDashboardMode
import com.example.chorevo.danceactivity.AchievementLevel
import com.example.chorevo.danceactivity.levelForPoints
import com.example.chorevo.danceactivity.nextLevelForPoints
import com.example.chorevo.videouploads.VideoUploadsTab
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chorevo.firebase.ViewModel.ProfileViewModel

private enum class ProfileTab(val title: String) {
    MY_DANCE("Activity History"),
    MY_UPLOADS("My Videos"),
    OTHER_ANALYTICS("Other Dance Analytics")
}

/** Displays the user profile screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    profile: UserProfile,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    analyticsVm: AnalyticsDashboardViewModel,
    videoUploadsVm: VideoUploadsViewModel,
    profileVm: ProfileViewModel
) {
    val brandTeal = Color(0xFF00B8C8)

    val surfaceColor = MaterialTheme.colorScheme.surface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val avatarBgColor = MaterialTheme.colorScheme.surfaceVariant
    val avatarIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    var tab by remember { mutableStateOf(ProfileTab.MY_DANCE) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by profileVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(profile.uid) {
        profileVm.refresh(profile.uid)
        refreshTick++
    }

    DisposableEffect(lifecycleOwner, profile.uid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profileVm.refresh(profile.uid)
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(brandTeal)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Profile",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }

                    TextButton(onClick = onLogout) {
                        Text("Logout", color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset(y = (-28).dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(avatarBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!profile.photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = profile.photoUrl,
                                contentDescription = "Profile photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = avatarIconColor,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (uiState.isLoading) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Refreshing profile…",
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor
                            )
                        }

                        status?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor
                            )
                        }
                    }
                }
            }
        }

        item {
            WeeklyStreakCard(
                daysMask = uiState.streak.daysMask,
                totalStreakActivities = uiState.streak.totalStreakActivities,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .offset(y = (-16).dp)
            )
        }

        item {
            AchievementPanel(
                currentLevel = currentLevel,
                nextLevel = nextLevel,
                totalPoints = uiState.points.totalPoints,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .offset(y = (-10).dp)
            )
        }

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
                    Tab(
                        selected = tab.ordinal == index,
                        onClick = { tab = t },
                        text = {
                            Text(
                                text = t.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (tab.ordinal == index) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (tab.ordinal == index) brandTeal else secondaryTextColor
                            )
                        }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item {
            when (tab) {
                ProfileTab.MY_DANCE -> {
                    AnalyticsDashboardView(
                        args = DanceDashboardArgs(
                            mode = DanceDashboardMode.MY_DANCE,
                            myUid = profile.uid
                        ),
                        title = "My Dance Sessions",
                        vm = analyticsVm,
                        modifier = Modifier.fillMaxWidth(),
                        embeddedInParentScroll = true
                    )
                }

                ProfileTab.MY_UPLOADS -> {
                    VideoUploadsTab(
                        onUploadDanceDemo = { },
                        embedded = true,
                        onStatus = { status = it },
                        vm = videoUploadsVm,
                        refreshKey = refreshTick,
                        modifier = Modifier.fillMaxWidth(),
                        embeddedInParentScroll = true
                    )
                }

                ProfileTab.OTHER_ANALYTICS -> {
                    AnalyticsDashboardView(
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
                }
            }
        }
    }
}

/** Handles weekly streak card. */
@Composable
private fun WeeklyStreakCard(
    daysMask: Int,
    totalStreakActivities: Int,
    modifier: Modifier = Modifier
) {
    val days = listOf("Mon", "Tues", "Wed", "Thurs", "Fri", "Sat", "Sun")
    val surfaceColor = MaterialTheme.colorScheme.surface
    val inactiveStreakColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Weekly Streak",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEachIndexed { idx, label ->
                    val done = ((daysMask shr idx) and 1) == 1
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (done) Color(0xFF16A34A) else inactiveStreakColor
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) {
                                Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "$totalStreakActivities Streak Activities",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Handles achievement panel. */
@Composable
private fun AchievementPanel(
    currentLevel: AchievementLevel,
    nextLevel: AchievementLevel?,
    totalPoints: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (nextLevel != null) {
        val range = nextLevel.pointsRequired - currentLevel.pointsRequired
        val soFar = totalPoints - currentLevel.pointsRequired
        (soFar.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    } else 1f

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Achievements", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${currentLevel.emoji} ${currentLevel.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$totalPoints pts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (nextLevel != null) {
                    Text(
                        text = "${nextLevel.pointsRequired - totalPoints} to ${nextLevel.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    Text("Max Level 🏆", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Color(0xFF00B8C8),
                trackColor = Color(0xFF00B8C8).copy(alpha = 0.2f)
            )
        }
    }
}
