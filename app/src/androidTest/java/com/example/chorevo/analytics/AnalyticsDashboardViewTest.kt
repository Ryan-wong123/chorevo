package com.example.chorevo.analytics

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.analytics.repository.AnalyticsRepository
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsDashboardViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTextExists(text: String) {
        assertTrue(
            "Expected text not found: $text",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun profile_viewSessionResults_showsSessions_expand_andShowsDetailContent() {
        val repo = mockk<AnalyticsRepository>()

        val session = DanceSessionSummary(
            sessionId = "s1",
            activityName = "Practice A",
            activityType = "DANCE",
            tsStart = 1000L,
            tsEnd = 4000L,
            durationMs = 3000L,
            alignmentScore = 0.91,
            smoothnessScore = 0.82,
            timingConsistencyScore = 0.73,
            avgPoseError = 0.123,
            frames = 99
        )

        every { repo.observeSessions(any(), any()) } returns flowOf(listOf(session))

        val detail = DanceSessionDetail(
            summary = session,
            analysis = AnalysisData(
                danceId = "dance1",
                educatorUid = "edu1",
                videoUrl = "",
                videoPositionMs = 0L,
                alignmentScore = 0.91,
                timingConsistencyScore = 0.73,
                smoothnessScore = 0.82,
                avgPoseError = 0.123,
                frames = 99,
                timelinePoints = listOf(
                    TimelinePoint(tMs = 0L, score = 0.5, err = 0.2),
                    TimelinePoint(tMs = 1000L, score = 0.7, err = 0.1)
                ),
                performanceSegments = listOf(
                    PerformanceSegment(
                        startMs = 0L,
                        endMs = 1000L,
                        label = SegmentLabel.OK,
                        avgScore = 0.6,
                        avgErr = 0.2
                    )
                )
            )
        )

        every { repo.observeDetail(any(), any()) } returns flowOf(detail)

        val vm = AnalyticsDashboardViewModel(repo)

        composeRule.setContent {
            AnalyticsDashboardView(
                args = DanceDashboardArgs(
                    mode = DanceDashboardMode.MY_DANCE,
                    myUid = "u1"
                ),
                vm = vm,
                title = "Profile Sessions"
            )
        }

        waitForTextExists("Practice A")
        composeRule.onNodeWithText("Practice A").performClick()

        waitForTextExists("Template Video")
        waitForTextExists("Performance Segments")
        waitForTextExists("Alignment Timeline")
        waitForTextExists("Mirror Off")
        waitForTextExists("Alignment: 91")
        waitForTextExists("Smooth: 82")
        waitForTextExists("Timing: 73")
        waitForTextExists("0.123")           // StatCard shows value only, no "AvgPoseError:" prefix
        waitForTextExists("99")              // StatCard shows value only, no "Frames:" prefix
        waitForTextExists("Pose Error")      // StatCard label is separate Text
        waitForTextExists("Frames")

        assertTextExists("Template Video")
        assertTextExists("Performance Segments")
        assertTextExists("Alignment Timeline")
        assertTextExists("Mirror Off")
        assertTextExists("Alignment: 91")
        assertTextExists("Smooth: 82")
        assertTextExists("Timing: 73")
        assertTextExists("0.123")
        assertTextExists("99")
        assertTextExists("Pose Error")
        assertTextExists("Frames")

        composeRule.onNodeWithText("Mirror Off").performClick()
        waitForTextExists("Mirror On")
        assertTextExists("Mirror On")
    }

    @Test
    fun profile_otherDanceAnalytics_mode_rendersSessions_andDetail() {
        val repo = mockk<AnalyticsRepository>()

        val sessionKey = "sess123||users/learner1/danceLearningSessions/sess123"

        val session = DanceSessionSummary(
            sessionId = sessionKey,
            activityName = "Practice B • learner1",
            activityType = "DANCE",
            tsStart = 1111L,
            tsEnd = 2222L,
            durationMs = 1111L,
            alignmentScore = 0.5,
            smoothnessScore = 0.6,
            timingConsistencyScore = 0.7,
            avgPoseError = null,
            frames = null
        )

        every { repo.observeSessions(any(), any()) } returns flowOf(listOf(session))

        val detail = DanceSessionDetail(
            summary = session,
            analysis = null
        )

        every { repo.observeDetail(any(), any()) } returns flowOf(detail)

        val vm = AnalyticsDashboardViewModel(repo)

        composeRule.setContent {
            AnalyticsDashboardView(
                args = DanceDashboardArgs(
                    mode = DanceDashboardMode.OTHER_ANALYTICS,
                    myUid = "educator1",
                    uploadedDanceId = "danceX"
                ),
                vm = vm,
                title = "Other Dance Analytics"
            )
        }

        waitForTextExists("Practice B • learner1")
        composeRule.onNodeWithText("Practice B • learner1").performClick()

        waitForTextExists("No segment data found.")
        waitForTextExists("No timeline data found.")

        assertTextExists("No segment data found.")
        assertTextExists("No timeline data found.")
    }
}
