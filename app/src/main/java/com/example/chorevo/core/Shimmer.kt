package com.example.chorevo.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Animated loading placeholder: a soft highlight sweeping across the surface
 * colour. Use in place of "Loading…" text while content is on its way.
 * Pass [base]/[highlight] to use it over media (e.g. the black feed).
 */
fun Modifier.shimmer(base: Color? = null, highlight: Color? = null): Modifier = composed {
    val baseColor = base ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val highlightColor = highlight ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress"
    )
    background(
        Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(progress * 600f - 300f, 0f),
            end = Offset(progress * 600f, 300f)
        )
    )
}

/** Shimmering block with the theme's small corner radius. */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(MaterialTheme.shapes.small).shimmer())
}

/** Placeholder for a list of cards (e.g. analytics sessions or leaderboard rows). */
@Composable
fun ShimmerList(rows: Int = 4, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shimmerList"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .shimmer()
                )
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShimmerBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
                    ShimmerBlock(Modifier.fillMaxWidth(0.45f).height(12.dp))
                }
            }
        }
    }
}

/** Placeholder for a 3-column thumbnail grid. */
@Composable
fun ShimmerGrid(cells: Int = 6, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shimmerGrid"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        (0 until cells).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(9f / 16f)
                            .shimmer()
                    )
                }
            }
        }
    }
}
