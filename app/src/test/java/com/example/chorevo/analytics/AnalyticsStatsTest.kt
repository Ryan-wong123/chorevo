package com.example.chorevo.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AnalyticsStatsTest {

    private fun session(
        id: String,
        tsStart: Long,
        alignment: Double,
        durationMs: Long = 60_000L,
        focus: Map<BodyArea, Int> = emptyMap()
    ) = DanceSessionSummary(
        sessionId = id,
        activityName = "Dance $id",
        activityType = "DANCE",
        tsStart = tsStart,
        tsEnd = tsStart + durationMs,
        durationMs = durationMs,
        alignmentScore = alignment,
        smoothnessScore = alignment / 2,
        timingConsistencyScore = alignment / 3,
        avgPoseError = null,
        frames = null,
        bodyFocus = focus
    )

    @Test
    fun bodyFocus_countsCorrectionsPerArea_andIgnoresPraise() {
        val focus = bodyFocusFromFeedback(
            listOf(
                "Raise your left arm higher.",
                "Lower your left arm.",
                "Straighten your right leg.",
                "Reset your posture and match the reference shape.",
                "Match the hand shape.",
                "Left hand not detected.",
                "Nice match. Keep the timing steady.",
                "Good hand shape."
            )
        )
        assertEquals(2, focus[BodyArea.LEFT_ARM])
        assertEquals(1, focus[BodyArea.RIGHT_LEG])
        assertEquals(1, focus[BodyArea.POSTURE])
        assertEquals(2, focus[BodyArea.HANDS])
        assertNull(focus[BodyArea.RIGHT_ARM])
    }

    @Test
    fun overview_emptySessions_isAllZero() {
        val stats = computeOverviewStats(emptyList(), ZoneOffset.UTC)
        assertEquals(0, stats.sessionCount)
        assertEquals(0.0, stats.averageScore, 0.0)
        assertEquals(0L, stats.totalPracticeMs)
        assertNull(stats.scoreChangePoints)
        assertTrue(stats.alignmentTrend.isEmpty())
        assertNull(stats.topFocusArea)
    }

    @Test
    fun overview_computesAverageBestTimeAndOrderedTrend() {
        // Given newest-first, like the repository returns them.
        val sessions = listOf(
            session("c", tsStart = 3_000, alignment = 0.9),
            session("a", tsStart = 1_000, alignment = 0.5),
            session("b", tsStart = 2_000, alignment = 0.7)
        )
        val stats = computeOverviewStats(sessions, ZoneOffset.UTC)

        assertEquals(3, stats.sessionCount)
        assertEquals(0.7, stats.averageScore, 1e-9)
        assertEquals(0.9, stats.bestScore, 1e-9)
        assertEquals(180_000L, stats.totalPracticeMs)
        assertEquals(listOf(0.5, 0.7, 0.9), stats.alignmentTrend)
        assertNull(stats.scoreChangePoints) // fewer than 4 sessions
    }

    @Test
    fun overview_scoreChange_comparesRecentHalfWithOlderHalf() {
        val sessions = listOf(
            session("1", 1_000, 0.40),
            session("2", 2_000, 0.50),
            session("3", 3_000, 0.70),
            session("4", 4_000, 0.80)
        )
        // Older avg 0.45, recent avg 0.75 → +30 points.
        assertEquals(30, computeOverviewStats(sessions, ZoneOffset.UTC).scoreChangePoints)
    }

    @Test
    fun overview_trendKeepsOnlyMostRecentPoints() {
        val sessions = (1..20).map { session("$it", it * 1_000L, it / 100.0) }
        val trend = computeOverviewStats(sessions, ZoneOffset.UTC).alignmentTrend
        assertEquals(AnalyticsOverviewStats.TREND_POINTS, trend.size)
        assertEquals(0.20, trend.last(), 1e-9)
    }

    @Test
    fun overview_groupsSessionsByDay_andSumsBodyFocus() {
        val day = 86_400_000L
        val sessions = listOf(
            session("a", 0, 0.5, focus = mapOf(BodyArea.LEFT_ARM to 2)),
            session("b", 3_600_000, 0.5, focus = mapOf(BodyArea.LEFT_ARM to 1, BodyArea.POSTURE to 1)),
            session("c", day, 0.5, focus = mapOf(BodyArea.RIGHT_LEG to 2))
        )
        val stats = computeOverviewStats(sessions, ZoneOffset.UTC)

        assertEquals(2, stats.sessionsPerDay[LocalDate.of(1970, 1, 1)])
        assertEquals(1, stats.sessionsPerDay[LocalDate.of(1970, 1, 2)])
        assertEquals(3, stats.bodyFocus[BodyArea.LEFT_ARM])
        assertEquals(BodyArea.LEFT_ARM, stats.topFocusArea)
    }

    @Test
    fun formatPracticeTime_formatsHoursMinutesSeconds() {
        assertEquals("45s", formatPracticeTime(45_000))
        assertEquals("12m", formatPracticeTime(12 * 60_000L))
        assertEquals("1h 05m", formatPracticeTime(65 * 60_000L))
    }
}
