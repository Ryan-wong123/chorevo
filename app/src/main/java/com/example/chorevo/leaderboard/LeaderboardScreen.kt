package com.example.chorevo.leaderboard

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
import com.example.chorevo.leaderboard.ViewModel.LeaderboardViewModel

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

    val brandTeal = Color(0xFF00B8C8)
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
                    containerColor = brandTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
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

            state.myEntry?.let { myEntry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = myCardBg
                    ),
                    border = BorderStroke(1.dp, brandTeal.copy(alpha = 0.30f))
                ) {
                    Column(Modifier.padding(12.dp)) {
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

                Spacer(Modifier.height(12.dp))
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = brandTeal,
                    trackColor = brandTeal.copy(alpha = 0.18f)
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!state.isLoading && state.entries.isEmpty()) {
                Text(
                    text = if (state.scope == LeaderboardScope.FRIENDS) {
                        "No friends added yet."
                    } else {
                        "No ranking data yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.entries,
                        key = { "${it.uid}_${it.rank}" }
                    ) { entry ->
                        val canAddFriend =
                            state.scope == LeaderboardScope.GLOBAL &&
                                    !entry.isMe &&
                                    entry.uid !in state.friendIds

                        val canRemoveFriend =
                            state.scope == LeaderboardScope.FRIENDS &&
                                    !entry.isMe &&
                                    entry.uid in state.friendIds

                        val isAdding = state.addingFriendUid == entry.uid
                        val isRemoving = state.removingFriendUid == entry.uid

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
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

                                when {
                                    isAdding || isRemoving -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = brandTeal
                                        )
                                    }

                                    canAddFriend -> {
                                        IconButton(onClick = { vm.addFriend(entry) }) {
                                            Icon(
                                                imageVector = Icons.Outlined.PersonAdd,
                                                contentDescription = "Add friend",
                                                tint = brandTeal
                                            )
                                        }
                                    }

                                    canRemoveFriend -> {
                                        IconButton(onClick = { vm.removeFriend(entry) }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove friend",
                                                tint = colors.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
