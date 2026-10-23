package com.example.chorevo.leaderboard

import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.ShimmerList
import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.chorevo.leaderboard.viewmodel.LeaderboardViewModel

/** Displays the leaderboard screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    myUid: String,
    vm: LeaderboardViewModel
) {
    LaunchedEffect(myUid) {
        vm.bindUser(myUid)
    }

    val state = vm.uiState.collectAsStateWithLifecycle().value
    val colors = MaterialTheme.colorScheme

    val brandTeal = ChorevoTheme.colors.accent
    val screenBg = colors.surface
    val cardBg = colors.surfaceVariant.copy(alpha = 0.35f)
    val myCardBg = brandTeal.copy(alpha = 0.12f)

    Scaffold(
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Leaderboard",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBg)
                .padding(padding)
                .padding(16.dp)
        ) {

            TabRow(
                selectedTabIndex = state.scope.ordinal,
                containerColor = Color.Transparent,
                contentColor = brandTeal,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[state.scope.ordinal]),
                        color = brandTeal
                    )
                }
            ) {
                Tab(
                    selected = state.scope == LeaderboardScope.GLOBAL,
                    onClick = { vm.setScope(LeaderboardScope.GLOBAL) },
                    selectedContentColor = brandTeal,
                    unselectedContentColor = colors.onSurfaceVariant,
                    text = { Text("Global") }
                )
                Tab(
                    selected = state.scope == LeaderboardScope.FRIENDS,
                    onClick = { vm.setScope(LeaderboardScope.FRIENDS) },
                    selectedContentColor = brandTeal,
                    unselectedContentColor = colors.onSurfaceVariant,
                    text = { Text("Friends") }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LeaderboardMetric.entries.forEach { metric ->
                    FilterChip(
                        selected = state.metric == metric,
                        onClick = { vm.setMetric(metric) },
                        label = { Text(metric.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = cardBg,
                            labelColor = colors.onSurfaceVariant,
                            selectedContainerColor = brandTeal.copy(alpha = 0.14f),
                            selectedLabelColor = colors.onSurface
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (state.metric == metric) brandTeal.copy(alpha = 0.35f)
                            else colors.outlineVariant
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.isLoading && state.entries.isEmpty()) {
                ShimmerList(rows = 6)
            } else if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = brandTeal,
                    trackColor = brandTeal.copy(alpha = 0.18f)
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!state.isLoading && state.entries.isEmpty()) {
                if (state.scope == LeaderboardScope.FRIENDS) {
                    AnimatedEmptyState(
                        icon = Icons.Filled.Group,
                        title = "No friends added yet.",
                        message = "Add dancers from the Global board to compare scores."
                    )
                } else {
                    AnimatedEmptyState(
                        icon = Icons.Filled.EmojiEvents,
                        title = "No ranking data yet.",
                        message = "Finish a practice session to claim the first spot."
                    )
                }
            } else if (state.entries.isNotEmpty()) {
                val podium = state.entries.take(3)
                val rest = state.entries.drop(3)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "podium") {
                        Podium(
                            entries = podium,
                            metric = state.metric,
                            brandTeal = brandTeal,
                            friendAction = { entry -> friendActionFor(entry, state, vm) }
                        )
                    }

                    items(
                        items = rest,
                        key = { "${it.uid}_${it.rank}" }
                    ) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = if (entry.isMe) myCardBg else cardBg
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (entry.isMe) brandTeal.copy(alpha = 0.30f)
                                else colors.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    LeaderboardRow(
                                        entry = entry,
                                        metric = state.metric,
                                        highlightMe = entry.isMe,
                                        brandTeal = brandTeal
                                    )
                                }
                                FriendActionButton(friendActionFor(entry, state, vm), brandTeal)
                            }
                        }
                    }
                }
            }

            // Your rank stays pinned under the list, wherever you are on the board.
            state.myEntry?.let { myEntry ->
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("myPositionCard"),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = myCardBg),
                    border = BorderStroke(1.dp, brandTeal.copy(alpha = 0.30f))
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "Your position",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = brandTeal
                        )
                        Spacer(Modifier.height(6.dp))
                        LeaderboardRow(
                            entry = myEntry,
                            metric = state.metric,
                            highlightMe = true,
                            brandTeal = brandTeal
                        )
                    }
                }
            }
        }
    }
}

/** What the add/remove-friend control should do for [entry], if anything. */
private sealed interface FriendAction {
    data object Busy : FriendAction
    data class Add(val onClick: () -> Unit) : FriendAction
    data class Remove(val onClick: () -> Unit) : FriendAction
}

private fun friendActionFor(
    entry: LeaderboardEntry,
    state: LeaderboardUiState,
    vm: LeaderboardViewModel
): FriendAction? = when {
    entry.isMe -> null
    state.addingFriendUid == entry.uid || state.removingFriendUid == entry.uid -> FriendAction.Busy
    state.scope == LeaderboardScope.GLOBAL && entry.uid !in state.friendIds ->
        FriendAction.Add { vm.addFriend(entry) }
    state.scope == LeaderboardScope.FRIENDS && entry.uid in state.friendIds ->
        FriendAction.Remove { vm.removeFriend(entry) }
    else -> null
}

@Composable
private fun FriendActionButton(action: FriendAction?, brandTeal: Color, small: Boolean = false) {
    val buttonSize = if (small) 32.dp else 48.dp
    when (action) {
        null -> Unit
        FriendAction.Busy -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = brandTeal
        )
        is FriendAction.Add -> IconButton(onClick = action.onClick, modifier = Modifier.size(buttonSize)) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = "Add friend",
                tint = brandTeal
            )
        }
        is FriendAction.Remove -> IconButton(onClick = action.onClick, modifier = Modifier.size(buttonSize)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove friend",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

// Medal colours for the podium (not part of the brand palette on purpose).
private val GoldColor = Color(0xFFFFC94D)
private val SilverColor = Color(0xFFC3CAD4)
private val BronzeColor = Color(0xFFD38B5D)

private fun medalColor(rank: Int): Color = when (rank) {
    1 -> GoldColor
    2 -> SilverColor
    else -> BronzeColor
}

/** Short metric value shown on the podium. */
private fun podiumValue(entry: LeaderboardEntry, metric: LeaderboardMetric): String = when (metric) {
    LeaderboardMetric.TOTAL_SESSIONS -> "${entry.totalPracticeSessions} sessions"
    LeaderboardMetric.AVG_SIMILARITY -> "${(entry.avgSimilarityScore * 100).roundToInt()}% match"
    LeaderboardMetric.TOTAL_ACTIVE_DAYS -> "${entry.totalActiveDays} days"
}

/**
 * Top three on a podium: 2nd on the left, 1st raised in the middle with a
 * crown, 3rd on the right. The blocks rise in when the board loads.
 */
@Composable
private fun Podium(
    entries: List<LeaderboardEntry>,
    metric: LeaderboardMetric,
    brandTeal: Color,
    friendAction: (LeaderboardEntry) -> FriendAction?
) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    // Display order: 2nd, 1st, 3rd.
    val slots = listOf(1, 0, 2).map { entries.getOrNull(it) }
    val blockHeights = listOf(78.dp, 108.dp, 58.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .testTag("podium"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        slots.forEachIndexed { index, entry ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (entry != null) {
                    PodiumPerson(
                        entry = entry,
                        metric = metric,
                        brandTeal = brandTeal,
                        isFirst = entry.rank == 1,
                        friendAction = friendAction(entry)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(blockHeights[index] * rise.value)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        medalColor(entry.rank).copy(alpha = 0.55f),
                                        medalColor(entry.rank).copy(alpha = 0.12f)
                                    )
                                )
                            )
                            .testTag("podium_rank_${entry.rank}"),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = entry.rank.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumPerson(
    entry: LeaderboardEntry,
    metric: LeaderboardMetric,
    brandTeal: Color,
    isFirst: Boolean,
    friendAction: FriendAction?
) {
    val medal = medalColor(entry.rank)
    val avatarSize = if (isFirst) 76.dp else 60.dp

    if (isFirst) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = medal,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(2.dp))
    }
    Box(
        modifier = Modifier
            .size(avatarSize)
            .border(3.dp, medal, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(brandTeal.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        if (!entry.photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = entry.photoUrl,
                contentDescription = "Profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = entry.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = brandTeal
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (entry.isMe) "${entry.displayName} (You)" else entry.displayName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (entry.isMe) brandTeal else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
    Text(
        text = podiumValue(entry, metric),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
    FriendActionButton(friendAction, brandTeal, small = true)
}

/** Handles leaderboard row. */
@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    metric: LeaderboardMetric,
    highlightMe: Boolean,
    brandTeal: Color
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#${entry.rank}",
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (highlightMe) brandTeal else colors.onSurface
        )

        if (!entry.photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = entry.photoUrl,
                contentDescription = "Profile picture",
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(brandTeal.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = brandTeal
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
        ) {
            Text(
                text = if (entry.isMe) "${entry.displayName} (You)" else entry.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = when (metric) {
                    LeaderboardMetric.TOTAL_SESSIONS ->
                        "Sessions: ${entry.totalPracticeSessions}"

                    LeaderboardMetric.AVG_SIMILARITY ->
                        "Average similarity: ${"%.2f".format(entry.avgSimilarityScore)}"

                    LeaderboardMetric.TOTAL_ACTIVE_DAYS ->
                        "Active days: ${entry.totalActiveDays}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}
