package com.example.chorevo.analytics

import com.example.chorevo.analytics.repository.AnalyticsRepository
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `MY_DANCE - sessions flow comes from repo observeSessions`() = runTest {
        val repo = mockk<AnalyticsRepository>()

        val sessions = listOf(
            DanceSessionSummary(
                sessionId = "s1",
                activityName = "Practice A",
                activityType = "DANCE",
                tsStart = 1000L,
                tsEnd = 2000L,
                durationMs = 1000L,
                alignmentScore = 0.9,
                smoothnessScore = 0.8,
                timingConsistencyScore = 0.7,
                avgPoseError = null,
                frames = null
            )
        )

        every { repo.observeSessions(any(), any()) } returns flowOf(sessions)
        every { repo.observeDetail(any(), any()) } returns flowOf(null)

        val vm = AnalyticsDashboardViewModel(repo)
        vm.setArgs(DanceDashboardArgs(mode = DanceDashboardMode.MY_DANCE, myUid = "u1"))

        val collectJob = backgroundScope.launch {
            vm.sessions.collect { }
        }

        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { repo.observeSessions(any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun `setArgs resets selectedDetail to null until a session is selected`() = runTest {
        val repo = mockk<AnalyticsRepository>()

        every { repo.observeSessions(any(), any()) } returns flowOf(emptyList())
        every { repo.observeDetail(any(), any()) } returns flowOf(null)

        val vm = AnalyticsDashboardViewModel(repo)
        vm.setArgs(DanceDashboardArgs(mode = DanceDashboardMode.MY_DANCE, myUid = "u1"))

        assertNull(vm.selectedDetail.value)
    }

    @Test
    fun `selectSession triggers observeDetail with the selected sessionKey`() = runTest {
        val repo = mockk<AnalyticsRepository>()

        every { repo.observeSessions(any(), any()) } returns flowOf(emptyList())

        val args = DanceDashboardArgs(mode = DanceDashboardMode.MY_DANCE, myUid = "u1")

        val detail = DanceSessionDetail(
            summary = DanceSessionSummary(
                sessionId = "s1",
                activityName = "Practice A",
                activityType = "DANCE",
                tsStart = 1000L,
                tsEnd = 2000L,
                durationMs = 1000L,
                alignmentScore = 0.9,
                smoothnessScore = 0.8,
                timingConsistencyScore = 0.7,
                avgPoseError = null,
                frames = null
            ),
            analysis = null
        )

        every { repo.observeDetail(eq(args), eq("s1")) } returns flowOf(detail)

        val vm = AnalyticsDashboardViewModel(repo)
        vm.setArgs(args)

        // 🔥 CRITICAL FIX: manually collect selectedDetail
        val collectJob = backgroundScope.launch {
            vm.selectedDetail.collect { }
        }

        vm.selectSession("s1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(atLeast = 1) { repo.observeDetail(eq(args), eq("s1")) }

        collectJob.cancel()
    }

    @Test
    fun `OTHER_ANALYTICS - observeDetail accepts sessionKey with docPath`() = runTest {
        val repo = mockk<AnalyticsRepository>()

        every { repo.observeSessions(any(), any()) } returns flowOf(emptyList())

        val args = DanceDashboardArgs(
            mode = DanceDashboardMode.OTHER_ANALYTICS,
            myUid = "educator1",
            uploadedDanceId = "danceX"
        )

        val sessionKey = "sess123||users/learner1/danceLearningSessions/sess123"

        val detail = DanceSessionDetail(
            summary = DanceSessionSummary(
                sessionId = sessionKey,
                activityName = "Other Practice",
                activityType = "DANCE",
                tsStart = 1000L,
                tsEnd = 2000L,
                durationMs = 1000L,
                alignmentScore = 0.5,
                smoothnessScore = 0.6,
                timingConsistencyScore = 0.7,
                avgPoseError = null,
                frames = null
            ),
            analysis = null
        )

        every { repo.observeDetail(eq(args), eq(sessionKey)) } returns flowOf(detail)

        val vm = AnalyticsDashboardViewModel(repo)
        vm.setArgs(args)

        // 🔥 CRITICAL FIX: manually collect selectedDetail
        val collectJob = backgroundScope.launch {
            vm.selectedDetail.collect { }
        }

        vm.selectSession(sessionKey)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(atLeast = 1) { repo.observeDetail(eq(args), eq(sessionKey)) }

        collectJob.cancel()
    }
}
