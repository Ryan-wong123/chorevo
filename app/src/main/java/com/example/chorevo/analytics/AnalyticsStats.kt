package com.example.chorevo.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Body areas the live feedback talks about; used by the body diagram. */
enum class BodyArea(val label: String) {
    LEFT_ARM("Left arm"),
    RIGHT_ARM("Right arm"),
    LEFT_LEG("Left leg"),
    RIGHT_LEG("Right leg"),
    POSTURE("Posture"),
    HANDS("Hands")
}

/**
 * Counts how often each body area was corrected in a session's feedback.
 * Praise ("Nice match…", "Good hand shape.") is not a correction and is ignored.
 */
fun bodyFocusFromFeedback(messages: List<String>): Map<BodyArea, Int> {
    val counts = mutableMapOf<BodyArea, Int>()
    for (raw in messages) {
        val msg = raw.lowercase()
        if (msg.startsWith("nice") || msg.startsWith("good")) continue
        val area = when {
            "left arm" in msg -> BodyArea.LEFT_ARM
            "right arm" in msg -> BodyArea.RIGHT_ARM
            "left leg" in msg -> BodyArea.LEFT_LEG
            "right leg" in msg -> BodyArea.RIGHT_LEG
            "hand" in msg -> BodyArea.HANDS
            "posture" in msg -> BodyArea.POSTURE
            else -> null
        } ?: continue
        counts[area] = (counts[area] ?: 0) + 1
    }
    return counts
}

/** Everything the analytics overview shows, computed from the session list. */
data class AnalyticsOverviewStats(
    val sessionCount: Int,
    /** Mean alignment (0–1) across sessions. */
    val averageScore: Double,
    val bestScore: Double,
    val totalPracticeMs: Long,
    /** Recent half's average minus the older half's, in points (0–100); null with < 4 sessions. */
    val scoreChangePoints: Int?,
    /** Oldest → newest, at most [TREND_POINTS]. */
    val alignmentTrend: List<Double>,
    val smoothnessTrend: List<Double>,
    val timingTrend: List<Double>,
    /** Sessions per day for the heatmap. */
    val sessionsPerDay: Map<LocalDate, Int>,
    /** Corrections per body area summed across sessions. */
    val bodyFocus: Map<BodyArea, Int>
) {
    /** Body area corrected most often, or null when there is no feedback. */
    val topFocusArea: BodyArea?
        get() = bodyFocus.filterValues { it > 0 }.maxByOrNull { it.value }?.key

    companion object {
        const val TREND_POINTS = 12
    }
}

/** Builds [AnalyticsOverviewStats] from sessions in any order. */
fun computeOverviewStats(
    sessions: List<DanceSessionSummary>,
    zone: ZoneId = ZoneId.systemDefault()
): AnalyticsOverviewStats {
    val chronological = sessions.sortedBy { it.tsStart }
    val scores = chronological.map { it.alignmentScore }

    val change = if (scores.size >= 4) {
        val half = scores.size / 2
        val older = scores.take(half).average()
        val recent = scores.takeLast(half).average()
        ((recent - older) * 100).roundToInt()
    } else {
        null
    }

    val recent = chronological.takeLast(AnalyticsOverviewStats.TREND_POINTS)
    val perDay = chronological
        .groupingBy { Instant.ofEpochMilli(it.tsStart).atZone(zone).toLocalDate() }
        .eachCount()

    val focus = mutableMapOf<BodyArea, Int>()
    chronological.forEach { s ->
        s.bodyFocus.forEach { (area, n) -> focus[area] = (focus[area] ?: 0) + n }
    }

    return AnalyticsOverviewStats(
        sessionCount = sessions.size,
        averageScore = if (scores.isEmpty()) 0.0 else scores.average(),
        bestScore = scores.maxOrNull() ?: 0.0,
        totalPracticeMs = chronological.sumOf { it.durationMs.coerceAtLeast(0L) },
        scoreChangePoints = change,
        alignmentTrend = recent.map { it.alignmentScore },
        smoothnessTrend = recent.map { it.smoothnessScore },
        timingTrend = recent.map { it.timingConsistencyScore },
        sessionsPerDay = perDay,
        bodyFocus = focus
    )
}

/** "1h 05m", "12m" or "45s". */
fun formatPracticeTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${"%02d".format(minutes)}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
