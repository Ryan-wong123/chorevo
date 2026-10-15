package com.example.chorevo.danceactivity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape

/** Live score at or above this counts as a good match (green) and builds the combo. */
internal const val LIVE_GOOD_SCORE = 0.65

/** Live score at or above this is an OK match (amber); below is red. */
internal const val LIVE_OK_SCORE = 0.45

/** The combo badge appears after this many seconds of continuous good matching. */
internal const val COMBO_MIN_SECONDS = 3

private val GoodGreen = Color(0xFF34D399)
private val OkAmber = Color(0xFFFBBF24)
private val BadRed = Color(0xFFF87171)

/**
 * Accuracy colour for the learner skeleton and score ring. These are fixed
 * bright colours because they are drawn over the camera, not on a theme surface.
 */
internal fun liveAccuracyColor(score: Double?): Color = when {
    score == null -> Color.White
    score >= LIVE_GOOD_SCORE -> GoodGreen
    score >= LIVE_OK_SCORE -> OkAmber
    else -> BadRed
}

/** Circular score ring with the percentage in the middle. */
@Composable
internal fun LiveScoreRing(score: Double, modifier: Modifier = Modifier) {
    val clamped = score.coerceIn(0.0, 1.0).toFloat()
    val sweep by animateFloatAsState(clamped, animationSpec = tween(300), label = "scoreRingSweep")
    val color by animateColorAsState(liveAccuracyColor(score), label = "scoreRingColor")

    Box(
        modifier = modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .testTag("liveScoreRing"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, useCenter = false, style = stroke)
            drawArc(color, -90f, 360f * sweep, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(clamped * 100).toInt()}",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/** "Combo" badge counting seconds of continuous good matching. */
@Composable
internal fun ComboBadge(seconds: Int, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(seconds) {
        pop.snapTo(1.25f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val colors = ChorevoTheme.colors
    Row(
        modifier = modifier
            .scale(pop.value)
            .clip(PillShape)
            .background(colors.brandGradient)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("comboBadge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = colors.onGradient,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "COMBO ${seconds}s",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onGradient
        )
    }
}

/** Big gradient 3-2-1 countdown; each number pops in. */
@Composable
internal fun PracticeCountdown(secondsRemaining: Int, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(secondsRemaining) {
        pop.snapTo(0.6f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val colors = ChorevoTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Get Ready",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier
                .clip(PillShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .size(160.dp)
                .scale(pop.value)
                .clip(CircleShape)
                .background(colors.brandGradient),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$secondsRemaining",
                style = MaterialTheme.typography.displayLarge,
                color = colors.onGradient
            )
        }
    }
}

/** Compact stat (label over percentage) for the practice controls panel. */
@Composable
internal fun StatPill(label: String, value: Double, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
