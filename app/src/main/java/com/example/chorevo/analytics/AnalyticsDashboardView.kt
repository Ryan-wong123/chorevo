package com.example.chorevo.analytics

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import com.example.chorevo.ui.theme.SegmentColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Displays the analytics dashboard with a session list and expandable detail panel.
 * Loads sessions and selected session details from the ViewModel.
 */
@Composable
fun AnalyticsDashboardView(
    args: DanceDashboardArgs,
    modifier: Modifier = Modifier,
    title: String = "Sessions",
    emptyText: String = "No sessions found.",
    vm: AnalyticsDashboardViewModel,
    embeddedInParentScroll: Boolean = false
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val detail by vm.selectedDetail.collectAsStateWithLifecycle()

    val expandedSessionIdState = rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(args) {
        vm.setArgs(args)
        expandedSessionIdState.value = null
    }

    LaunchedEffect(expandedSessionIdState.value, args.mode, detail?.summary?.sessionId) {
        val sid = expandedSessionIdState.value ?: return@LaunchedEffect

        if (args.mode == DanceDashboardMode.MY_DANCE && sid.contains("||")) {
            expandedSessionIdState.value = null
            return@LaunchedEffect
        }

        if (sid.isNotBlank() && detail?.summary?.sessionId != sid) {
            vm.selectSession(sid)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        ExpandableAnalyticsList(
            items = sessions,
            keyOf = { it.sessionId },
            titleOf = { it.activityName },
            subtitleOf = { formatDate(it.tsStart) },
            expandedKeyState = expandedSessionIdState,
            emptyText = emptyText,
            embeddedInParentScroll = embeddedInParentScroll,
            summaryRow = { s ->
                MetricChip("Alignment", (s.alignmentScore * 100).toInt().toString())
                MetricChip("Smooth", (s.smoothnessScore * 100).toInt().toString())
                MetricChip("Timing", (s.timingConsistencyScore * 100).toInt().toString())
            },
            expandedContent = { s ->
                val selected = detail?.takeIf { it.summary.sessionId == s.sessionId }

                if (selected == null) {
                    Card(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Loading analytics…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else {
                    SessionDetailPanel(detail = selected, modifier = Modifier.fillMaxWidth())
                }
            }
        )
    }
}
/**
 * Displays a reusable expandable list for analytics items.
 * Supports optional summary content and expanded detail content.
 */
@Composable
fun <T> ExpandableAnalyticsList(
    items: List<T>,
    keyOf: (T) -> String,
    titleOf: (T) -> String,
    subtitleOf: (T) -> String?,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here yet.",
    expandedKeyState: MutableState<String?>,
    embeddedInParentScroll: Boolean = false,
    summaryRow: (@Composable RowScope.(T) -> Unit)? = null,
    expandedContent: @Composable (T) -> Unit
) {
    if (items.isEmpty()) {
        Surface(modifier = modifier.fillMaxWidth()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    if (embeddedInParentScroll) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { item ->
                val k = keyOf(item)
                val isExpanded = expandedKeyState.value == k

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedKeyState.value = if (isExpanded) null else k },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = titleOf(item),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                subtitleOf(item)?.let { sub ->
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = null
                            )
                        }

                        if (summaryRow != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                content = { summaryRow(item) }
                            )
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn(tween(120)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                expandedContent(item)
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { keyOf(it) }) { item ->
                val k = keyOf(item)
                val isExpanded = expandedKeyState.value == k

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedKeyState.value = if (isExpanded) null else k },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = titleOf(item),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                subtitleOf(item)?.let { sub ->
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = null
                            )
                        }

                        if (summaryRow != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                content = { summaryRow(item) }
                            )
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn(tween(120)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                expandedContent(item)
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Handles metric chip. */
@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Formats date for display. */
fun formatDate(ts: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ts))
}

/** Handles session detail panel. */
@Composable
fun SessionDetailPanel(
    detail: DanceSessionDetail,
    modifier: Modifier = Modifier
) {
    val analysis = detail.analysis
    val summary = detail.summary

    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    LaunchedEffect(analysis?.videoUrl) {
        val url = analysis?.videoUrl
        if (!url.isNullOrBlank()) {
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
        }
    }

    val durationT = remember(analysis) {
        val last = analysis?.timelinePoints?.lastOrNull()?.tMs
        max(last ?: 0L, summary.durationMs)
    }

    var playheadMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exoPlayer) {
        while (true) {
            playheadMs = exoPlayer.currentPosition
            delay(50)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── STATS ROW ──────────────────────────────────────────────────────────
        // CHANGED: was a plain Card with small text. Now 3 pill stat cards in a row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                label = "Duration",
                value = "${"%.1f".format(summary.durationMs / 1000.0)}s",
                modifier = Modifier.weight(1f)
            )
            analysis?.avgPoseError?.let {
                StatCard(
                    label = "Pose Error",
                    value = "%.3f".format(it),
                    modifier = Modifier.weight(1f)
                )
            }
            analysis?.frames?.let {
                StatCard(
                    label = "Frames",
                    value = it.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── VIDEO ──────────────────────────────────────────────────────────────
        // UNCHANGED structure, just kept as-is
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Template Video", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            player = exoPlayer
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            useController = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }
        }

        // ── PERFORMANCE SEGMENTS ───────────────────────────────────────────────
        // CHANGED: added legend row below the heat bar so user knows what colours mean
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Title
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Performance Segments", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap a row to jump to that moment in the video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                if (analysis == null || analysis.performanceSegments.isEmpty()) {
                    Text(
                        "No segment data found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    val good = analysis.performanceSegments.count { it.label == SegmentLabel.GOOD }
                    val ok = analysis.performanceSegments.count { it.label == SegmentLabel.OK }
                    val bad = analysis.performanceSegments.count { it.label == SegmentLabel.BAD }

                    // ── DONUT replaces the 3 score chips ──────────────────────────────
                    SegmentDonutChart(
                        good = good,
                        ok = ok,
                        bad = bad,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 1.dp
                    )

                    // ── GANTT replaces the heat bar + segment rows ─────────────────────
                    Text(
                        "Timeline Breakdown",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    GanttSegmentChart(
                        segments = analysis.performanceSegments,
                        totalMs = durationT,
                        currentMs = playheadMs,
                        onSeek = { exoPlayer.seekTo(it) }
                    )
                }
            }
        }

        // ── ALIGNMENT TIMELINE ─────────────────────────────────────────────────
        // CHANGED: added score labels on Y-axis (1.0 / 0.5 / 0.0) and a coloured
        // background band so users can read the chart without needing to guess the axis.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Alignment Timeline", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "How well your moves matched the template over time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                if (analysis == null || analysis.timelinePoints.isEmpty()) {
                    Text(
                        "No timeline data found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    TimelineChart(
                        points = analysis.timelinePoints,
                        currentMs = playheadMs,
                        onSeek = { exoPlayer.seekTo(it) }
                    )
                }
            }
        }
    }
}

// ── NEW: compact stat pill ─────────────────────────────────────────────────────
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {

    val blue = Color(0xFF2196F3)
    val blueLight = Color(0xFFBBDEFB)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = blueLight
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = blue
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = blue.copy(alpha = 0.8f)
            )
        }
    }
}

// ── NEW: legend dot + label ────────────────────────────────────────────────────
@Composable
fun SegmentLegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(999.dp))
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ── UPDATED SegmentHeatBar: taller, rounded, cleaner playhead ─────────────────
@Composable
fun SegmentHeatBar(
    segments: List<PerformanceSegment>,
    totalMs: Long,
    currentMs: Long,
    onSeek: (Long) -> Unit
) {
    val safeTotal = max(1L, totalMs)
    val primaryLine = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // CHANGED: height 30dp → 44dp for easier tapping; added time labels below
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp) // CHANGED: was 30dp
                .background(surfaceVariant, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                segments.forEach { seg ->
                    val segDur = max(0L, seg.endMs - seg.startMs)
                    val weight = segDur.toFloat() / safeTotal.toFloat()
                    val goodColor = SegmentColors.good
                    val okColor = SegmentColors.ok
                    val badColor = SegmentColors.bad
                    val color = when (seg.label) {
                        SegmentLabel.GOOD -> goodColor
                        SegmentLabel.OK -> okColor
                        SegmentLabel.BAD -> badColor
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(max(0.001f, weight))
                            .background(color, RoundedCornerShape(10.dp))
                            .pointerInput(Unit) { detectTapGestures { onSeek(seg.startMs) } }
                    )
                }
            }

            // Tap anywhere on bar to seek
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(safeTotal) {
                        detectTapGestures { tap ->
                            val ratio = (tap.x / size.width).coerceIn(0f, 1f)
                            onSeek((ratio * safeTotal.toFloat()).toLong())
                        }
                    }
            )

            // Playhead
            Canvas(modifier = Modifier.matchParentSize()) {
                val ratio = (currentMs.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                val x = ratio * size.width
                // CHANGED: added a white glow behind the line for contrast on any colour
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(x, 0f), end = Offset(x, size.height),
                    strokeWidth = 6f
                )
                drawLine(
                    color = primaryLine,
                    start = Offset(x, 0f), end = Offset(x, size.height),
                    strokeWidth = 3f
                )
                drawCircle(color = primaryLine, radius = 6f, center = Offset(x, size.height / 2f))
            }
        }

        // CHANGED: time labels at start and end so user knows the duration scale
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "0:00", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                formatMmSs(safeTotal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

// ── UPDATED TimelineChart: Y-axis labels, threshold bands, better readability ──
@Composable
fun TimelineChart(
    points: List<TimelinePoint>,
    currentMs: Long,
    onSeek: (Long) -> Unit
) {
    val primary = Color(0xFF2196F3)
    val pointerColor = MaterialTheme.colorScheme.onSurface
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val goodBand = SegmentColors.good.copy(alpha = 0.07f)
    val badBand = SegmentColors.bad.copy(alpha = 0.07f)

    val sorted = remember(points) { points.sortedBy { it.tMs } }
    val minT = sorted.first().tMs
    val maxT = max(sorted.last().tMs, minT + 1)

    // CHANGED: added left padding for Y-axis labels using Row layout
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp) // CHANGED: was 180dp, more breathing room
    ) {
        // Y-axis labels column
        // CHANGED: new — previously there were no Y-axis labels at all
        Column(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .padding(bottom = 16.dp), // align with chart bottom (accounts for X label row)
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "1.0", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Text(
                "0.5", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Text(
                "0.0", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }

        // Chart area
        Column(modifier = Modifier
            .weight(1f)
            .fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(minT, maxT) {
                        detectTapGestures { tap ->
                            val ratio = (tap.x / size.width).coerceIn(0f, 1f)
                            onSeek((minT + ratio * (maxT - minT)).toLong())
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // CHANGED: coloured background bands — green top third = good,
                    //          red bottom third = bad, so user reads quality intuitively
                    drawRect(
                        color = goodBand, topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(w, h * 0.33f)
                    )
                    drawRect(
                        color = badBand,
                        topLeft = Offset(0f, h * 0.67f),
                        size = androidx.compose.ui.geometry.Size(w, h * 0.33f)
                    )

                    // CHANGED: dashed-style gridlines at 0.25 / 0.5 / 0.75
                    listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                        val y = (1f - frac) * h
                        drawLine(
                            color = axisColor, start = Offset(0f, y),
                            end = Offset(w, y), strokeWidth = 1f
                        )
                    }

                    // Bottom axis
                    drawLine(
                        color = axisColor, start = Offset(0f, h),
                        end = Offset(w, h), strokeWidth = 2f
                    )

                    // Score line — CHANGED: added a subtle filled area under the line
                    val fillPath = Path()
                    sorted.forEachIndexed { idx, p ->
                        val x = ((p.tMs - minT).toFloat() / (maxT - minT).toFloat()) * w
                        val y = (1f - p.score.toFloat().coerceIn(0f, 1f)) * h
                        if (idx == 0) fillPath.moveTo(x, y) else fillPath.lineTo(x, y)
                    }
                    // close fill path to bottom
                    fillPath.lineTo(((sorted.last().tMs - minT).toFloat() / (maxT - minT)) * w, h)
                    fillPath.lineTo(0f, h)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        color = primary.copy(alpha = 0.12f)
                    )

                    // Score line
                    val linePath = Path()
                    sorted.forEachIndexed { idx, p ->
                        val x = ((p.tMs - minT).toFloat() / (maxT - minT).toFloat()) * w
                        val y = (1f - p.score.toFloat().coerceIn(0f, 1f)) * h
                        if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                    }
                    drawPath(path = linePath, color = primary, style = Stroke(width = 3f))

                    // Dots
                    sorted.forEach { p ->
                        val x = ((p.tMs - minT).toFloat() / (maxT - minT).toFloat()) * w
                        val y = (1f - p.score.toFloat().coerceIn(0f, 1f)) * h
                        drawCircle(color = primary, radius = 4f, center = Offset(x, y))
                        // CHANGED: white centre on dot for clarity
                        drawCircle(color = Color.White, radius = 2f, center = Offset(x, y))
                    }

                    // Playhead
                    val clamped = currentMs.coerceIn(minT, maxT)
                    val px = ((clamped - minT).toFloat() / (maxT - minT).toFloat()) * w
                    drawLine(
                        color = pointerColor.copy(alpha = 0.8f),
                        start = Offset(px, 0f), end = Offset(px, h), strokeWidth = 2f
                    )
                    drawCircle(color = pointerColor, radius = 5f, center = Offset(px, 0f))
                }
            }

            // CHANGED: X-axis time labels at bottom (start / mid / end)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatMmSs(minT), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    formatMmSs((minT + maxT) / 2), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    formatMmSs(maxT), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ── NEW helper: format milliseconds as "m:ss" ─────────────────────────────────
fun formatMmSs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

// ── NEW: score summary chip (count + % per label) ──────────────────────────────
@Composable
fun SegmentScoreChip(
    color: Color,
    bgColor: Color,
    label: String,
    count: Int,
    pct: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Coloured dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, RoundedCornerShape(999.dp))
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.titleSmall,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = "$count seg",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

// ── NEW: individual segment row ────────────────────────────────────────────────
@Composable
fun SegmentRow(
    index: Int,
    segment: PerformanceSegment,
    onClick: () -> Unit
) {
    val goodColor = SegmentColors.good
    val okColor = SegmentColors.ok
    val badColor = SegmentColors.bad
    val color = when (segment.label) {
        SegmentLabel.GOOD -> goodColor
        SegmentLabel.OK -> okColor
        SegmentLabel.BAD -> badColor
    }
    val labelText = when (segment.label) {
        SegmentLabel.GOOD -> "Good"
        SegmentLabel.OK -> "OK"
        SegmentLabel.BAD -> "Needs Work"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Segment number
            Text(
                text = "#$index",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.width(20.dp)
            )

            // Coloured label badge
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = color.copy(alpha = 0.15f)
            ) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // Time range
            Text(
                text = "${formatMmSs(segment.startMs)} → ${formatMmSs(segment.endMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )

            // Avg score
            Text(
                text = "${"%.0f".format(segment.avgScore * 100)}%",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

// ── NEW: Donut chart replacing the 3 score chips ──────────────────────────────
@Composable
fun SegmentDonutChart(
    good: Int,
    ok: Int,
    bad: Int,
    modifier: Modifier = Modifier
) {
    val total = (good + ok + bad).toFloat().takeIf { it > 0f } ?: 1f

    val goodColor = SegmentColors.good
    val okColor = SegmentColors.ok
    val badColor = SegmentColors.bad

    // Sweep angles proportional to count
    val goodSweep = (good / total) * 360f
    val okSweep = (ok / total) * 360f
    val badSweep = (bad / total) * 360f

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Donut
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 28f
                val inset = strokeWidth / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    size.width - strokeWidth,
                    size.height - strokeWidth
                )
                val topLeft = Offset(inset, inset)
                var startAngle = -90f  // start from top

                // Draw each arc segment
                listOf(
                    Triple(goodSweep, goodColor, good),
                    Triple(okSweep, okColor, ok),
                    Triple(badSweep, badColor, bad)
                ).forEach { (sweep, color, _) ->
                    if (sweep > 0f) {
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep - 2f, // 2f gap between segments
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                        startAngle += sweep
                    }
                }
            }

            // Centre label: dominant segment
            val dominantLabel = when {
                good >= ok && good >= bad -> "Good"
                ok >= good && ok >= bad -> "OK"
                else -> "Work\nOn It"
            }
            val dominantColor = when {
                good >= ok && good >= bad -> goodColor
                ok >= good && ok >= bad -> okColor
                else -> badColor
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dominantLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = dominantColor
                )
                Text(
                    text = "${(maxOf(good, ok, bad) / total * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = dominantColor
                )
            }
        }

        // Legend column beside the donut
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DonutLegendRow(
                color = goodColor,
                label = "Good",
                count = good,
                pct = (good / total * 100).toInt()
            )
            DonutLegendRow(
                color = okColor,
                label = "OK",
                count = ok,
                pct = (ok / total * 100).toInt()
            )
            DonutLegendRow(
                color = badColor,
                label = "Needs Work",
                count = bad,
                pct = (bad / total * 100).toInt()
            )
        }
    }
}

// ── NEW: single legend row used by donut ──────────────────────────────────────
@Composable
fun DonutLegendRow(color: Color, label: String, count: Int, pct: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Colour swatch
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        // Label + count
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$count seg · $pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        // Pct bar — visual proportion indicator
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .height(6.dp)
                .width((pct * 0.6f).dp)  // scale: 100% = 60dp wide
                .background(color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
        )
    }
}

// ── NEW: Gantt-style bar replacing the heat bar ───────────────────────────────
@Composable
fun GanttSegmentChart(
    segments: List<PerformanceSegment>,
    totalMs: Long,
    currentMs: Long,
    onSeek: (Long) -> Unit
) {
    val safeTotal = max(1L, totalMs)
    val goodColor = SegmentColors.good
    val okColor = SegmentColors.ok
    val badColor = SegmentColors.bad
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // Filter out zero-duration segments
    val validSegments = segments.filter { it.endMs > it.startMs }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        validSegments.forEachIndexed { idx, seg ->
            val color = when (seg.label) {
                SegmentLabel.GOOD -> goodColor
                SegmentLabel.OK -> okColor
                SegmentLabel.BAD -> badColor
            }
            val labelText = when (seg.label) {
                SegmentLabel.GOOD -> "Good"
                SegmentLabel.OK -> "OK"
                SegmentLabel.BAD -> "Needs Work"
            }

            val startRatio = seg.startMs.toFloat() / safeTotal.toFloat()
            val widthRatio = (seg.endMs - seg.startMs).toFloat() / safeTotal.toFloat()
            val durationSec = (seg.endMs - seg.startMs) / 1000

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(seg.startMs) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row number
                Text(
                    text = "#${idx + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.width(22.dp)
                )

                // Gantt bar track
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .background(surfaceVariant, RoundedCornerShape(6.dp))
                ) {
                    // The filled segment bar, offset by startRatio
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(startRatio + widthRatio)  // total width up to end
                            .padding(start = (startRatio * 1000).dp.coerceAtMost(300.dp)) // approximate offset
                    )

                    // Draw the actual bar using Canvas for precise positioning
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val barStart = startRatio * size.width
                        val barWidth = (widthRatio * size.width).coerceAtLeast(6f)

                        // Background already handled by Box
                        // Draw segment fill
                        drawRoundRect(
                            color = color.copy(alpha = 0.85f),
                            topLeft = Offset(barStart, 0f),
                            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f)
                        )

                        // Playhead line if within this segment
                        if (currentMs in seg.startMs..seg.endMs) {
                            val playRatio =
                                (currentMs - seg.startMs).toFloat() / (seg.endMs - seg.startMs).toFloat()
                            val playX = barStart + playRatio * barWidth
                            drawLine(
                                color = onSurface,
                                start = Offset(playX, 0f),
                                end = Offset(playX, size.height),
                                strokeWidth = 3f
                            )
                        }
                    }
                }

                // Label badge
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.width(80.dp)
                ) {
                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }

                // Score + duration stacked
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.width(42.dp)
                ) {
                    Text(
                        text = "${"%.0f".format(seg.avgScore * 100)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = color
                    )
                    Text(
                        text = "${durationSec}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        }

        // ── Time axis below all rows ──────────────────────────────────────────
        // Offset by the row number width (22dp) + spacer (8dp) = 30dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 30.dp, end = 122.dp), // align with bar track only
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
            Text(
                formatMmSs(safeTotal / 2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
            Text(
                formatMmSs(safeTotal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}
