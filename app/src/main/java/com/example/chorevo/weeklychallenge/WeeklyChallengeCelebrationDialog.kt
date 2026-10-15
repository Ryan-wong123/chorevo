package com.example.chorevo.weeklychallenge

import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Celebration shown when a practice session causes one or more weekly challenges
 * (or the all-three bonus) to complete. Mirrors the styling of the existing
 * AchievementSummaryDialog in [com.example.chorevo.danceactivity.DanceARPracticeScreen],
 * and is shown right after it so the two celebrations never stack.
 */
@Composable
fun WeeklyChallengeCelebrationDialog(
    newlyCompleted: List<WeeklyChallenge>,
    bonusNewlyUnlocked: Boolean,
    bonusRewardPoints: Int,
    onDismiss: () -> Unit
) {
    val brandTeal = ChorevoTheme.colors.accent
    val totalPoints = newlyCompleted.sumOf { it.rewardPoints } +
        if (bonusNewlyUnlocked) bonusRewardPoints else 0

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Challenge Complete!", fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Text(
                    text = "+$totalPoints pts",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = brandTeal
                )

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    newlyCompleted.forEach { challenge ->
                        CelebrationRow(
                            emoji = challenge.emoji,
                            title = challenge.title,
                            subtitle = "+${challenge.rewardPoints} pts",
                            brandTeal = brandTeal,
                            highlight = false
                        )
                    }

                    if (bonusNewlyUnlocked) {
                        CelebrationRow(
                            emoji = "🎉",
                            title = "All three quests cleared!",
                            subtitle = "Bonus +$bonusRewardPoints pts",
                            brandTeal = brandTeal,
                            highlight = true
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = ChorevoTheme.colors.onAccent
                    )
                ) {
                    Text("Awesome!", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CelebrationRow(
    emoji: String,
    title: String,
    subtitle: String,
    brandTeal: Color,
    highlight: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = brandTeal.copy(alpha = if (highlight) 0.14f else 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(emoji, fontSize = 24.sp)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}
