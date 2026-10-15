package com.example.chorevo.analytics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.SegmentColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/** Number of weeks shown in the activity heatmap. */
private const val HEATMAP_WEEKS = 8

/**
 * Summary above the session list: number tiles, a score trend chart, an
 * activity heatmap and a body diagram of what to work on.
 */
@Composable
fun AnalyticsOverview(
    sessions: List<DanceSessionSummary>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
) {
    val stats = remember(sessions, zone) { computeOverviewStats(sessions, zone) }
    val colors = ChorevoTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("analyticsOverview"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Avg score",
                value = "${(stats.averageScore * 100).roundToInt()}%",
                caption = stats.scoreChangePoints?.let { change ->
                    when {
                        change > 0 -> "▲ $change pts recently"
                        change < 0 -> "▼ ${abs(change)} pts recently"
                        else -> "Steady"
                    }
                },
                captionColor = when {
                    (stats.scoreChangePoints ?: 0) > 0 -> SegmentColors.good
                    (stats.scoreChangePoints ?: 0) < 0 -> SegmentColors.bad
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                sparkline = stats.alignmentTrend,
                accent = colors.accent,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Best score",
                value = "${(stats.bestScore * 100).roundToInt()}%",
                caption = "Personal high",
                accent = colors.like,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Sessions",
                value = stats.sessionCount.toString(),
                caption = "${stats.sessionsPerDay.keys.count { !it.isBefore(today.minusDays(6)) }} active days this week",
                accent = colors.accent,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Practice time",
                value = formatPracticeTime(stats.totalPracticeMs),
                caption = "Total dancing",
                accent = colors.like,
                modifier = Modifier.weight(1f)
            )
        }

        if (stats.alignmentTrend.size >= 2) {
            TrendCard(stats)
        }
        ActivityHeatmap(perDay = stats.sessionsPerDay, today = today)
        BodyFocusCard(stats)
    }
}

@Composable
private fun OverviewCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/** Big-number tile with an optional caption and sparkline. */
@Composable
private fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    sparkline: List<Double> = emptyList()
) {
    Surface(
        modifier = modifier.testTag("statTile_$label"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sparkline.size >= 2) {
                Sparkline(
                    values = sparkline,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            }
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = captionColor, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawTrendLine(values, color, strokeWidth = 2.dp.toPx(), fillAlpha = 0.18f)
    }
}

/** Draws 0–1 values as a line across the canvas, optionally with a soft fill. */
private fun DrawScope.drawTrendLine(
    values: List<Double>,
    color: Color,
    strokeWidth: Float,
    fillAlpha: Float = 0f,
    progress: Float = 1f
) {
    if (values.size < 2) return
    val stepX = size.width / (values.size - 1)
    fun point(i: Int) = Offset(
        x = i * stepX,
        y = size.height - (values[i].coerceIn(0.0, 1.0).toFloat() * size.height)
    )

    // Points up to the animated progress, ending part-way along a segment.
    val visible = ((values.size - 1) * progress.coerceIn(0f, 1f)).coerceAtLeast(0f)
    val whole = visible.toInt()
    val pts = (0..whole).map { point(it) }.toMutableList()
    val frac = visible - whole
    if (frac > 0f && whole + 1 < values.size) {
        val a = point(whole)
        val b = point(whole + 1)
        pts += Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
    }

    val line = Path().apply {
        moveTo(pts.first().x, pts.first().y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    if (fillAlpha > 0f && pts.size >= 2) {
        val fill = Path().apply {
            moveTo(pts.first().x, size.height)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(pts.last().x, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = fillAlpha))
    }
    drawPath(
        line,
        color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** Alignment, smoothness and timing over the most recent sessions; draws itself in. */
@Composable
private fun TrendCard(stats: AnalyticsOverviewStats) {
    val colors = ChorevoTheme.colors
    val timingColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val drawIn = remember { Animatable(0f) }
    LaunchedEffect(stats.alignmentTrend) {
        drawIn.snapTo(0f)
        drawIn.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    OverviewCard(title = "Score trend", modifier = Modifier.testTag("trendCard")) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                val y = size.height * (1f - f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            drawTrendLine(stats.timingTrend, timingColor.copy(alpha = 0.8f), 2.dp.toPx(), progress = drawIn.value)
            drawTrendLine(stats.smoothnessTrend, colors.like.copy(alpha = 0.8f), 2.dp.toPx(), progress = drawIn.value)
            drawTrendLine(stats.alignmentTrend, colors.accent, 3.dp.toPx(), fillAlpha = 0.15f, progress = drawIn.value)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(colors.accent, "Alignment")
            LegendDot(colors.like, "Smoothness")
            LegendDot(timingColor, "Timing")
        }
        Text(
            "Last ${stats.alignmentTrend.size} sessions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * GitHub-style grid: one column per week (oldest left), one row per weekday
 * (Mon at the top). Darker cells mean more sessions that day.
 */
@Composable
private fun ActivityHeatmap(perDay: Map<LocalDate, Int>, today: LocalDate) {
    val accent = ChorevoTheme.colors.accent
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val firstMonday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .minusWeeks((HEATMAP_WEEKS - 1).toLong())
    val windowDays = perDay.filterKeys { !it.isBefore(firstMonday) && !it.isAfter(today) }
    val activeDays = windowDays.size

    fun cellColor(count: Int): Color = when {
        count <= 0 -> empty
        count == 1 -> accent.copy(alpha = 0.4f)
        count == 2 -> accent.copy(alpha = 0.7f)
        else -> accent
    }

    OverviewCard(
        title = "Activity",
        modifier = Modifier.testTag("activityHeatmap"),
        trailing = {
            Text(
                "$activeDays active days · $HEATMAP_WEEKS weeks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "", "W", "", "F", "", "S").forEach { d ->
                    Box(Modifier.size(width = 14.dp, height = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text(d, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            repeat(HEATMAP_WEEKS) { week ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(7) { dayOfWeek ->
                        val date = firstMonday.plusWeeks(week.toLong()).plusDays(dayOfWeek.toLong())
                        val future = date.isAfter(today)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (future) Color.Transparent else cellColor(perDay[date] ?: 0))
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(0, 1, 2, 3).forEach { n ->
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(cellColor(n))
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Front-facing figure coloured by how often each body area was corrected:
 * green (rarely) through amber to red (most often). The dancer's left is on
 * the viewer's right, matching the camera mirror the learner sees.
 */
@Composable
private fun BodyFocusCard(stats: AnalyticsOverviewStats) {
    val good = SegmentColors.good
    val ok = SegmentColors.ok
    val bad = SegmentColors.bad
    val neutral = MaterialTheme.colorScheme.outline
    val maxCount = stats.bodyFocus.values.maxOrNull() ?: 0
    val hasData = maxCount > 0

    fun areaColor(area: BodyArea): Color {
        if (!hasData) return neutral
        val share = (stats.bodyFocus[area] ?: 0).toFloat() / maxCount
        return if (share <= 0.5f) lerp(good, ok, share * 2f) else lerp(ok, bad, (share - 0.5f) * 2f)
    }

    OverviewCard(title = "What to work on", modifier = Modifier.testTag("bodyDiagram")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .width(110.dp)
                    .aspectRatio(0.62f)
            ) {
                drawBodyFigure(
                    posture = areaColor(BodyArea.POSTURE),
                    leftArm = areaColor(BodyArea.LEFT_ARM),
                    rightArm = areaColor(BodyArea.RIGHT_ARM),
                    leftLeg = areaColor(BodyArea.LEFT_LEG),
                    rightLeg = areaColor(BodyArea.RIGHT_LEG),
                    hands = areaColor(BodyArea.HANDS)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasData) {
                    Text(
                        "Not enough feedback yet",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Practise a few routines and the parts of your body that need the most attention will light up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    stats.topFocusArea?.let { top ->
                        Text(
                            "Focus on: ${top.label.lowercase()}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    stats.bodyFocus.entries
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (area, count) ->
                            FocusBar(
                                label = area.label,
                                count = count,
                                fraction = count.toFloat() / maxCount,
                                color = areaColor(area)
                            )
                        }
                    Text(
                        "Based on live corrections from your sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusBar(label: String, count: Int, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                "$count×",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** Simple front-facing stick figure; each limb gets its own colour. */
private fun DrawScope.drawBodyFigure(
    posture: Color,
    leftArm: Color,
    rightArm: Color,
    leftLeg: Color,
    rightLeg: Color,
    hands: Color
) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.09f
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    fun limb(color: Color, vararg pts: Pair<Float, Float>) {
        val points = pts.map { (x, y) -> p(x, y) }
        for (i in 0 until points.size - 1) {
            drawLine(color, points[i], points[i + 1], strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    // Head and torso (posture).
    drawCircle(posture, radius = w * 0.13f, center = p(0.5f, 0.11f))
    limb(posture, 0.5f to 0.24f, 0.5f to 0.55f)
    limb(posture, 0.3f to 0.28f, 0.7f to 0.28f)
    limb(posture, 0.38f to 0.55f, 0.62f to 0.55f)

    // Dancer's right arm/leg on the viewer's left.
    limb(rightArm, 0.3f to 0.28f, 0.16f to 0.42f, 0.1f to 0.56f)
    limb(leftArm, 0.7f to 0.28f, 0.84f to 0.42f, 0.9f to 0.56f)
    limb(rightLeg, 0.4f to 0.55f, 0.36f to 0.76f, 0.33f to 0.96f)
    limb(leftLeg, 0.6f to 0.55f, 0.64f to 0.76f, 0.67f to 0.96f)

    // Hands.
    drawCircle(hands, radius = w * 0.07f, center = p(0.1f, 0.58f))
    drawCircle(hands, radius = w * 0.07f, center = p(0.9f, 0.58f))
}
