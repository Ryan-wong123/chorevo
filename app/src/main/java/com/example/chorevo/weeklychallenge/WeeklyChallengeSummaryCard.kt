package com.example.chorevo.weeklychallenge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chorevo.ui.theme.SegmentColors

private val brandTeal = Color(0xFF00B8C8)

/**
 * Compact "AI Focus Quests" preview shown on [com.example.chorevo.firebase.UserProfileScreen],
 * styled to match the existing WeeklyStreakCard / AchievementPanel cards on that screen.
 * Tapping "View all" opens the full [WeeklyChallengesScreen].
 */
@Composable
fun WeeklyChallengeSummaryCard(
    challenges: List<WeeklyChallenge>,
    completedCount: Int,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Focus Quests",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$completedCount/${challenges.size.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(12.dp))

            if (challenges.isEmpty()) {
                Text(
                    text = "Complete a practice session to unlock this week's challenges.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            } else {
                challenges.forEach { challenge ->
                    MiniChallengeRow(challenge)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = onViewAll) {
                    Text("View all", color = brandTeal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MiniChallengeRow(challenge: WeeklyChallenge) {
    val progressColor = if (challenge.completed) SegmentColors.good else brandTeal

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(challenge.emoji, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = challenge.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { challenge.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.18f)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = if (challenge.completed) "✓" else "${challenge.progressCount}/${challenge.targetCount}",
            style = MaterialTheme.typography.labelSmall,
            color = if (challenge.completed) SegmentColors.good else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
