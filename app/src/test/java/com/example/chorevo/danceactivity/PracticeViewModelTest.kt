package com.example.chorevo.danceactivity

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.example.chorevo.analytics.SessionAnalysisPayload
import com.example.chorevo.core.telemetry.AnalyticsEvents
import com.example.chorevo.core.telemetry.Telemetry
import com.example.chorevo.danceactivity.viewmodel.PracticeUiState
import com.example.chorevo.danceactivity.viewmodel.PracticeViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val appContext = mockk<Context>(relaxed = true)
    private val personalBests = mockk<PersonalBestStore>()
    private val telemetry = mockk<Telemetry>(relaxed = true)
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        every { personalBests.recordIfBest(any(), any(), any()) } returns false
        // SessionAnalysisPayload.toJson uses org.json, which is only a stub on the plain JVM.
        mockkConstructor(SessionAnalysisPayload::class)
        every { anyConstructed<SessionAnalysisPayload>().toJson() } returns "{}"
    }

    @After
    fun tearDown() {
        unmockkConstructor(SessionAnalysisPayload::class)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun TestScope.newViewModel(): PracticeViewModel = PracticeViewModel(
        appContext = appContext,
        personalBests = personalBests,
        defaultDispatcher = StandardTestDispatcher(testScheduler),
        timerDispatcher = StandardTestDispatcher(testScheduler),
        savedState = savedState,
        telemetry = telemetry
    )

    private fun dance(
        template: PoseTemplate? = null,
        educatorUid: String? = "educator-1"
    ) = DanceLibraryModel(
        danceId = "d1",
        title = "Test Dance",
        educatorUid = educatorUid,
        videoUrl = "https://example.com/v.mp4",
        template = template
    )

    private fun pose(): List<Float> {
        val landmarks = MutableList(33 * 4) { 0f }
        for (idx in 0 until 33) {
            landmarks[idx * 4] = 0.5f
            landmarks[idx * 4 + 1] = 0.5f
            landmarks[idx * 4 + 3] = 1f
        }
        return landmarks
    }

    private fun poseFrames(count: Int): List<PoseFrame> =
        List(count) { PoseFrame(tMs = it * 1_000L, landmarks = pose()) }

    /** fps 1 and 10 frames -> three steps (4, 4 and 2 frames). */
    private fun steppedTemplate() = PoseTemplate(fps = 1, frames = poseFrames(10))

    /** Runs the 3 second countdown to completion (in virtual time). */
    private fun TestScope.finishCountdown() {
        advanceTimeBy(1_001)
        advanceTimeBy(1_001)
        advanceTimeBy(1_001)
    }

    // ── bind ─────────────────────────────────────────────────────────────────

    @Test
    fun bind_normalMode_startsIdleWithDefaultScores() = runTest {
        val vm = newViewModel()

        vm.bind(dance(), testMode = false)

        val state = vm.uiState.value
        assertFalse(state.hasCamPermission)
        assertFalse(state.isPracticeActive)
        assertFalse(state.isCountdownActive)
        assertNull(state.status)
        assertTrue(state.showReferenceSelector)
        assertEquals(0.0, state.alignmentScore, 0.0)
        assertEquals(1.0, state.timingConsistencyScore, 0.0)
        assertEquals(1.0, state.smoothnessScore, 0.0)
        assertEquals(0, state.frameCount)
        assertTrue(state.practiceSegments.isEmpty())
        assertNull(state.lastResult)
        vm.release()
    }

    @Test
    fun bind_testMode_startsActiveWithCannedScores() = runTest {
        val vm = newViewModel()

        vm.bind(dance(), testMode = true)

        val state = vm.uiState.value
        assertTrue(state.hasCamPermission)
        assertTrue(state.isPracticeActive)
        assertEquals("Test Mode", state.status)
        assertEquals(0.82, state.alignmentScore, 0.0)
        assertEquals(0.91, state.timingConsistencyScore, 0.0)
        assertEquals(0.88, state.smoothnessScore, 0.0)
        assertEquals(42, state.frameCount)
        assertFalse(vm.acceptsFrames)
        vm.release()
    }

    @Test
    fun bind_sameDanceTwice_keepsCurrentState() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.setStatus("custom")

        vm.bind(dance(), testMode = false)

        assertEquals("custom", vm.uiState.value.status)
        vm.release()
    }

    @Test
    fun bind_sameDanceWithDifferentTestMode_rebinds() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.setStatus("custom")

        vm.bind(dance(), testMode = true)

        assertEquals("Test Mode", vm.uiState.value.status)
        vm.release()
    }

    @Test
    fun bind_templateWithSeveralSteps_derivesSegmentsButStaysInFullRoutine() = runTest {
        val vm = newViewModel()

        vm.bind(dance(steppedTemplate()), testMode = false)

        val state = vm.uiState.value
        assertEquals(3, state.practiceSegments.size)
        assertEquals(PracticeMode.FULL_ROUTINE, state.practiceMode)
        assertEquals(0, state.currentStepIndex)
        assertNotNull(state.selectedReferenceTrack)
        assertEquals(1, state.availableReferenceTracks.size)
        assertNull(state.currentPracticeSegment)
        vm.release()
    }

    @Test
    fun bind_shortTemplate_hasSingleSegmentAndFullRoutine() = runTest {
        val vm = newViewModel()

        vm.bind(dance(PoseTemplate(fps = 1, frames = poseFrames(4))), testMode = false)

        val state = vm.uiState.value
        assertEquals(1, state.practiceSegments.size)
        assertEquals(PracticeMode.FULL_ROUTINE, state.practiceMode)
        vm.release()
    }

    @Test
    fun bind_emptyTemplate_hasNoTracksOrSegments() = runTest {
        val vm = newViewModel()

        vm.bind(dance(PoseTemplate(fps = 1)), testMode = false)

        val state = vm.uiState.value
        assertTrue(state.availableReferenceTracks.isEmpty())
        assertTrue(state.practiceSegments.isEmpty())
        assertNull(state.selectedReferenceTrack)
        vm.release()
    }

    @Test
    fun bind_restoresStepByStepFromSavedState_clampingStepIndex() = runTest {
        savedState["practice.danceId"] = "d1"
        savedState["practice.mode"] = "STEP_BY_STEP"
        savedState["practice.step"] = 99
        val vm = newViewModel()

        vm.bind(dance(steppedTemplate()), testMode = false)

        val state = vm.uiState.value
        assertEquals(PracticeMode.STEP_BY_STEP, state.practiceMode)
        assertEquals(2, state.currentStepIndex)
        assertEquals("Step 3 of 3", state.currentPracticeSegment?.title)
        vm.release()
    }

    @Test
    fun bind_ignoresSavedStateOfAnotherDance() = runTest {
        savedState["practice.danceId"] = "other"
        savedState["practice.mode"] = "STEP_BY_STEP"
        savedState["practice.step"] = 1
        val vm = newViewModel()

        vm.bind(dance(steppedTemplate()), testMode = false)

        assertEquals(PracticeMode.FULL_ROUTINE, vm.uiState.value.practiceMode)
        assertEquals(0, vm.uiState.value.currentStepIndex)
        vm.release()
    }

    // ── Reference track and step navigation ──────────────────────────────────

    @Test
    fun bind_withTwoTracks_picksTheLongestAndSelectReferenceTrackSwitches() = runTest {
        val long = PoseTrack(trackId = "a", label = "Dancer A", frames = poseFrames(10))
        val short = PoseTrack(trackId = "b", label = "Dancer B", frames = poseFrames(4))
        val vm = newViewModel()
        vm.bind(dance(PoseTemplate(fps = 1, tracks = listOf(long, short))), testMode = false)
        assertEquals("a", vm.uiState.value.selectedReferenceTrack?.trackId)
        assertEquals(2, vm.uiState.value.availableReferenceTracks.size)

        vm.selectReferenceTrack("b")

        val state = vm.uiState.value
        assertEquals("b", state.selectedReferenceTrack?.trackId)
        assertEquals("Using Dancer B. Tap Start Practice when ready.", state.status)
        // The shorter track has a single step, so step-by-step falls back to full routine.
        assertEquals(1, state.practiceSegments.size)
        vm.release()
    }

    @Test
    fun selectReferenceTrack_sameOrUnknownTrack_isIgnored() = runTest {
        val a = PoseTrack(trackId = "a", label = "Dancer A", frames = poseFrames(10))
        val vm = newViewModel()
        vm.bind(dance(PoseTemplate(fps = 1, tracks = listOf(a))), testMode = false)
        vm.setStatus("custom")

        vm.selectReferenceTrack("a")
        vm.selectReferenceTrack("missing")

        assertEquals("custom", vm.uiState.value.status)
        assertEquals("a", vm.uiState.value.selectedReferenceTrack?.trackId)
        vm.release()
    }

    @Test
    fun selectStepByStep_withoutSegments_doesNothing() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        vm.selectStepByStep()

        assertEquals(PracticeMode.FULL_ROUTINE, vm.uiState.value.practiceMode)
        assertNull(vm.uiState.value.status)
        vm.release()
    }

    @Test
    fun selectStepByStep_entersFirstStepAndPersistsSelection() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)

        vm.selectStepByStep()

        val state = vm.uiState.value
        assertEquals(PracticeMode.STEP_BY_STEP, state.practiceMode)
        assertEquals(0, state.currentStepIndex)
        assertEquals("Step 1 of 3 ready. Tap Start Practice when ready.", state.status)
        assertEquals("STEP_BY_STEP", savedState.get<String>("practice.mode"))
        assertEquals("d1", savedState.get<String>("practice.danceId"))
        vm.release()
    }

    @Test
    fun nextAndPreviousStep_moveWithinBounds() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()

        vm.previousStep()
        assertEquals(0, vm.uiState.value.currentStepIndex)

        vm.nextStep()
        assertEquals(1, vm.uiState.value.currentStepIndex)
        assertEquals("Step 2 of 3 ready. Tap Start Practice when ready.", vm.uiState.value.status)

        vm.nextStep()
        vm.nextStep()
        assertEquals(2, vm.uiState.value.currentStepIndex)

        vm.previousStep()
        assertEquals(1, vm.uiState.value.currentStepIndex)
        vm.release()
    }

    @Test
    fun selectFullRoutine_leavesStepMode() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()

        vm.selectFullRoutine()

        assertEquals(PracticeMode.FULL_ROUTINE, vm.uiState.value.practiceMode)
        assertEquals("Full routine ready. Tap Start Practice when ready.", vm.uiState.value.status)
        assertNull(vm.uiState.value.currentPracticeSegment)
        vm.release()
    }

    @Test
    fun onSecondaryAction_inStepMode_advancesThenRestartsLastStep() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()

        assertNull(vm.onSecondaryAction("u1"))
        assertEquals(1, vm.uiState.value.currentStepIndex)
        assertNull(vm.onSecondaryAction("u1"))
        assertEquals(2, vm.uiState.value.currentStepIndex)

        assertNull(vm.onSecondaryAction("u1"))
        assertEquals(2, vm.uiState.value.currentStepIndex)
        assertEquals("Final step ready. Tap Start Practice to repeat it.", vm.uiState.value.status)
        verify(exactly = 0) { personalBests.recordIfBest(any(), any(), any()) }
        vm.release()
    }

    @Test
    fun onFinishAction_inStepMode_completesStepWithoutSubmitting() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()
        vm.startPracticeCountdown()
        finishCountdown()
        assertTrue(vm.uiState.value.isPracticeActive)
        assertEquals("Step 1 of 3 in progress", vm.uiState.value.status)

        val submission = vm.onFinishAction("u1")

        assertNull(submission)
        val state = vm.uiState.value
        assertFalse(state.isPracticeActive)
        assertEquals("Step 1 of 3 complete. Tap Next Step to continue.", state.status)
        assertNull(state.lastResult)
        vm.release()
    }

    @Test
    fun onFinishAction_onLastStep_mentionsSwitchingToFullRoutine() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()
        vm.nextStep()
        vm.nextStep()
        vm.startPracticeCountdown()
        finishCountdown()

        vm.onFinishAction("u1")

        assertEquals(
            "Step 3 of 3 complete. Repeat it or switch to Full Routine to submit a session.",
            vm.uiState.value.status
        )
        vm.release()
    }

    // ── Countdown and timer ──────────────────────────────────────────────────

    @Test
    fun startPracticeCountdown_showsThreeAndHidesReferenceSelector() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        vm.startPracticeCountdown()

        val state = vm.uiState.value
        assertEquals(3, state.countdownSecondsRemaining)
        assertTrue(state.isCountdownActive)
        assertFalse(state.isPracticeActive)
        assertFalse(state.showReferenceSelector)
        assertEquals("Starting in 3...", state.status)
        vm.release()
    }

    @Test
    fun startPracticeCountdown_inStepMode_namesTheStep() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()

        vm.startPracticeCountdown()

        assertEquals("Starting step 1 of 3 in 3...", vm.uiState.value.status)
        vm.release()
    }

    @Test
    fun countdown_ticksDownEverySecondThenStartsPractice() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()

        advanceTimeBy(1_001)
        assertEquals(2, vm.uiState.value.countdownSecondsRemaining)
        assertEquals("Starting in 2...", vm.uiState.value.status)
        assertFalse(vm.uiState.value.isPracticeActive)

        advanceTimeBy(1_001)
        assertEquals(1, vm.uiState.value.countdownSecondsRemaining)
        assertEquals("Starting in 1...", vm.uiState.value.status)

        advanceTimeBy(1_001)
        val state = vm.uiState.value
        assertNull(state.countdownSecondsRemaining)
        assertTrue(state.isPracticeActive)
        assertEquals("Practice in progress", state.status)
        assertTrue(state.practiceTimeMs >= 0L)
        vm.release()
    }

    @Test
    fun countdown_doesNotStartPracticeBeforeThreeSeconds() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()

        advanceTimeBy(2_500)

        assertFalse(vm.uiState.value.isPracticeActive)
        assertEquals(1, vm.uiState.value.countdownSecondsRemaining)
        vm.release()
    }

    @Test
    fun countdown_whenPracticeStarts_logsPracticeStartedEvent() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        vm.startPracticeCountdown()
        finishCountdown()

        verify {
            telemetry.logEvent(
                AnalyticsEvents.PRACTICE_STARTED,
                mapOf(
                    AnalyticsEvents.PARAM_DANCE_ID to "d1",
                    AnalyticsEvents.PARAM_MODE to "full_routine"
                )
            )
        }
        vm.release()
    }

    @Test
    fun startPracticeCountdown_calledAgain_restartsFromThree() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()
        advanceTimeBy(1_001)
        assertEquals(2, vm.uiState.value.countdownSecondsRemaining)

        vm.startPracticeCountdown()

        assertEquals(3, vm.uiState.value.countdownSecondsRemaining)
        advanceTimeBy(1_001)
        assertEquals(2, vm.uiState.value.countdownSecondsRemaining)
        vm.release()
    }

    @Test
    fun testMode_timerKeepsPracticeActive() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        advanceTimeBy(200)

        assertTrue(vm.uiState.value.isPracticeActive)
        assertTrue(vm.uiState.value.practiceTimeMs >= 0L)
        vm.release()
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    @Test
    fun reset_duringCountdown_cancelsItAndShowsMessage() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()
        advanceTimeBy(1_001)

        vm.reset()

        val state = vm.uiState.value
        assertNull(state.countdownSecondsRemaining)
        assertFalse(state.isPracticeActive)
        assertTrue(state.showReferenceSelector)
        assertEquals("Reset", state.status)
        advanceTimeBy(10_000)
        assertFalse(vm.uiState.value.isPracticeActive)
        assertNull(vm.uiState.value.countdownSecondsRemaining)
        vm.release()
    }

    @Test
    fun reset_whilePracticing_stopsAndClearsLiveData() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()
        finishCountdown()
        assertTrue(vm.uiState.value.isPracticeActive)

        vm.reset("Try again")

        val state = vm.uiState.value
        assertFalse(state.isPracticeActive)
        assertEquals(0L, state.practiceTimeMs)
        assertNull(state.learnerLandmarks)
        assertNull(state.refLandmarksForCamera)
        assertEquals(0.0, state.alignmentScore, 0.0)
        assertEquals(0, state.frameCount)
        assertEquals("Try again", state.status)
        vm.release()
    }

    @Test
    fun reset_inTestMode_restoresCannedScoresAndStopsPractice() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        vm.reset()

        val state = vm.uiState.value
        assertFalse(state.isPracticeActive)
        assertEquals(0.82, state.alignmentScore, 0.0)
        assertEquals(42, state.frameCount)
        vm.release()
    }

    // ── Submit ───────────────────────────────────────────────────────────────

    @Test
    fun submit_withoutBoundDance_returnsNull() = runTest {
        val vm = newViewModel()

        assertNull(vm.onSecondaryAction("u1"))
        assertNull(vm.uiState.value.status)
    }

    @Test
    fun submit_beforeStartingPractice_isRejectedWithMessage() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        val submission = vm.onSecondaryAction("u1")

        assertNull(submission)
        assertEquals("Tap Start Practice before submitting a dance session.", vm.uiState.value.status)
        verify(exactly = 0) { personalBests.recordIfBest(any(), any(), any()) }
        vm.release()
    }

    @Test
    fun submit_withMissingEducator_isRejected() = runTest {
        val vm = newViewModel()
        vm.bind(dance(educatorUid = "   "), testMode = true)

        val submission = vm.onFinishAction("u1")

        assertNull(submission)
        assertEquals(
            "This dance is missing educator ownership data, so analytics cannot be submitted.",
            vm.uiState.value.status
        )
        verify(exactly = 0) { personalBests.recordIfBest(any(), any(), any()) }
        vm.release()
    }

    @Test
    fun submit_withNullEducator_isRejected() = runTest {
        val vm = newViewModel()
        vm.bind(dance(educatorUid = null), testMode = true)

        assertNull(vm.onFinishAction("u1"))
        vm.release()
    }

    @Test
    fun submit_goodScore_buildsSessionAndPersonalBestResult() = runTest {
        every { personalBests.recordIfBest("u1", "d1", 0.82) } returns true
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        val submission = vm.onFinishAction("u1")

        assertNotNull(submission)
        val session = submission!!.session
        assertEquals("Test Dance", session.activityName)
        assertEquals("LIVE_CAMERA_TEMPLATE", session.activityType)
        assertEquals("u1", session.ownerUid)
        assertEquals(0.82, session.alignmentScore, 0.0)
        assertEquals(0.91, session.timingConsistencyScore, 0.0)
        assertEquals(0.88, session.smoothnessScore, 0.0)
        assertEquals(1.0, session.balanceScore, 0.0)
        assertEquals("GOOD", session.qualityFlag)
        assertEquals("{}", session.analysisJson)
        assertTrue(session.sessionId.isNotBlank())
        assertEquals(session.tsEnd - session.tsStart, session.durationMs)
        assertFalse(session.synced)

        val state = vm.uiState.value
        assertFalse(state.isPracticeActive)
        assertEquals("Submitting practice result...", state.status)
        val result = state.lastResult!!
        assertEquals("d1", result.danceId)
        assertEquals("Test Dance", result.danceTitle)
        assertEquals(0.82, result.alignment, 0.0)
        assertEquals(0.91, result.timing, 0.0)
        assertEquals(0.88, result.smoothness, 0.0)
        assertTrue(result.isPersonalBest)
        verify(exactly = 1) { personalBests.recordIfBest("u1", "d1", 0.82) }
        vm.release()
    }

    @Test
    fun submit_notAPersonalBest_isReportedInResult() = runTest {
        every { personalBests.recordIfBest(any(), any(), any()) } returns false
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        vm.onFinishAction("u1")

        assertFalse(vm.uiState.value.lastResult!!.isPersonalBest)
        vm.release()
    }

    @Test
    fun submit_logsPracticeCompletedEvent() = runTest {
        every { personalBests.recordIfBest(any(), any(), any()) } returns true
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        vm.onFinishAction("u1")

        verify {
            telemetry.logEvent(
                eq(AnalyticsEvents.PRACTICE_COMPLETED),
                match {
                    it[AnalyticsEvents.PARAM_DANCE_ID] == "d1" &&
                        it[AnalyticsEvents.PARAM_SCORE] is Int &&
                        it[AnalyticsEvents.PARAM_PERSONAL_BEST] == true
                }
            )
        }
        vm.release()
    }

    @Test
    fun submit_lowAlignmentAfterRealCountdown_isFlaggedDone() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()
        finishCountdown()

        val submission = vm.onSecondaryAction("u1")

        assertNotNull(submission)
        assertEquals("DONE", submission!!.session.qualityFlag)
        assertEquals(0.0, submission.session.alignmentScore, 0.0)
        verify { personalBests.recordIfBest("u1", "d1", 0.0) }
        assertFalse(vm.uiState.value.isPracticeActive)
        vm.release()
    }

    @Test
    fun submit_duringCountdown_clearsCountdown() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)
        vm.startPracticeCountdown()

        // Not active and no frames yet, so it is rejected, but the countdown is dropped.
        val submission = vm.onSecondaryAction("u1")

        assertNull(submission)
        assertNull(vm.uiState.value.countdownSecondsRemaining)
        vm.release()
    }

    // ── Misc state ───────────────────────────────────────────────────────────

    @Test
    fun setStatus_updatesAndClearsMessage() = runTest {
        val vm = newViewModel()

        vm.setStatus("hello")
        assertEquals("hello", vm.uiState.value.status)

        vm.setStatus(null)
        assertNull(vm.uiState.value.status)
    }

    @Test
    fun onCameraPermissionResult_updatesPermissionFlag() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        vm.onCameraPermissionResult(granted = false)

        assertFalse(vm.uiState.value.hasCamPermission)
        vm.release()
    }

    @Test
    fun onCameraPermissionResult_inTestMode_alwaysHasPermission() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = true)

        vm.onCameraPermissionResult(granted = false)

        assertTrue(vm.uiState.value.hasCamPermission)
        vm.release()
    }

    @Test
    fun acceptsFrames_isFalseWithoutDetector() = runTest {
        val vm = newViewModel()
        vm.bind(dance(), testMode = false)

        assertFalse(vm.acceptsFrames)
        vm.release()
    }

    @Test
    fun onCameraFrame_withoutDetectorOrInTestMode_isIgnored() = runTest {
        val bitmap = mockk<Bitmap>()
        val normal = newViewModel()
        normal.bind(dance(), testMode = false)
        val before = normal.uiState.value

        normal.onCameraFrame(bitmap)

        assertEquals(before, normal.uiState.value)
        normal.release()

        val testing = newViewModel()
        testing.bind(dance(), testMode = true)
        val beforeTest = testing.uiState.value

        testing.onCameraFrame(bitmap)

        assertEquals(beforeTest, testing.uiState.value)
        testing.release()
    }

    @Test
    fun release_resetsStateAndClearsSavedSelection() = runTest {
        val vm = newViewModel()
        vm.bind(dance(steppedTemplate()), testMode = false)
        vm.selectStepByStep()
        assertEquals("d1", savedState.get<String>("practice.danceId"))

        vm.release()

        assertEquals(PracticeUiState(), vm.uiState.value)
        assertNull(savedState.get<String>("practice.danceId"))
        assertNull(savedState.get<String>("practice.mode"))
        assertNull(savedState.get<Int>("practice.step"))
    }
}
