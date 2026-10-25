package com.example.chorevo.weeklychallenge

import androidx.compose.runtime.ReadOnlyComposable
import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.ShimmerList
import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.ui.theme.SegmentColors
import com.example.chorevo.weeklychallenge.viewmodel.WeeklyChallengeViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val brandTeal: Color
    @Composable @ReadOnlyComposable get() = ChorevoTheme.colors.accent

/**
 * Full-screen view of this week's three personalized "AI Focus Quest" challenges.
 * Reached from the profile section, so the bottom nav stays on the Profile tab —
 * see [com.example.chorevo.core.routes.AppNavigationRoute].
 */
@Composable
fun WeeklyChallengesScreen(
    uid: String,
    vm: WeeklyChallengeViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(uid) {
        vm.loadForWeek(uid)
    }

    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val screenBg = colors.surface
    val cardBg = colors.surfaceVariant.copy(alpha = 0.35f)

    Scaffold(
        containerColor = screenBg,
        topBar = { WeeklyChallengesTopBar(onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = weekLabel(state.challengeSet.weekStart),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            WeeklyChallengeHeaderCard(
                completedCount = state.completedCount,
                totalCount = state.challenges.size,
                bonusRewardPoints = state.challengeSet.bonusRewardPoints,
                allCompleted = state.challengeSet.allCompleted,
                cardBg = cardBg
            )

            Spacer(Modifier.height(16.dp))

            if (state.loading && state.challenges.isEmpty()) {
                ShimmerList(rows = 4)
            } else if (state.challenges.isEmpty()) {
                AnimatedEmptyState(
                    icon = Icons.Filled.EmojiEvents,
                    title = if (state.errorMessage != null) "Couldn't load challenges" else "No challenges yet",
                    message = state.errorMessage ?: "No challenges available yet — check back after your next session.",
                    accent = brandTeal
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = state.challenges, key = { it.id }) { challenge ->
                        WeeklyChallengeCard(challenge = challenge, cardBg = cardBg)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyChallengesTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Weekly Challenges",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun WeeklyChallengeHeaderCard(
    completedCount: Int,
    totalCount: Int,
    bonusRewardPoints: Int,
    allCompleted: Boolean,
    cardBg: Color
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (totalCount <= 0) 0f else completedCount.toFloat() / totalCount.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$completedCount of $totalCount complete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (allCompleted) {
                    Text("🎉", style = MaterialTheme.typography.titleLarge)
                }
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (allCompleted) SegmentColors.good else brandTeal,
                trackColor = brandTeal.copy(alpha = 0.18f)
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (allCompleted) {
                    "Bonus +$bonusRewardPoints pts unlocked for clearing all three!"
                } else {
                    "Complete all three this week for a +$bonusRewardPoints pt bonus."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeeklyChallengeCard(challenge: WeeklyChallenge, cardBg: Color) {
    val colors = MaterialTheme.colorScheme
    val progressColor = if (challenge.completed) SegmentColors.good else brandTeal

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (challenge.completed) brandTeal.copy(alpha = 0.10f) else cardBg
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(challenge.emoji, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = challenge.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = challenge.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (challenge.completed) {
                    Text(
                        text = "✓",
                        color = SegmentColors.good,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { challenge.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.18f)
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${challenge.progressCount}/${challenge.targetCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = "+${challenge.rewardPoints} pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

private fun weekLabel(weekStart: String): String {
    return try {
        val start = LocalDate.parse(weekStart)
        val end = start.plusDays(6)
        val formatter = DateTimeFormatter.ofPattern("MMM d")
        "Week of ${start.format(formatter)} – ${end.format(formatter)}"
    } catch (_: Exception) {
        "This week"
    }
}
