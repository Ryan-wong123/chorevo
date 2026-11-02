package com.example.chorevo.posedetection

import com.example.chorevo.analytics.SegmentLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSimilarityEvaluatorExtraTest {

    // ------------------------------------------------------------
    // Basic scoring
    // ------------------------------------------------------------

    @Test
    fun identicalPose_scoresPerfect_andGivesNiceMatchFeedback() {
        val evaluator = evaluator(listOf(0L to samplePose()))

        evaluator.onFrame(samplePose(), timeMs = 0L)
        val snapshot = evaluator.snapshot()

        assertEquals(1, snapshot.frameCount)
        assertEquals(1.0, snapshot.alignmentScore, 1e-9)
        assertEquals(0.0, snapshot.avgPoseError, 1e-9)
        assertEquals(1.0, snapshot.liveScore!!, 1e-9)
        assertEquals("Nice match. Keep the timing steady.", snapshot.latestFeedback?.message)
        assertEquals("overall", snapshot.latestFeedback?.focusArea)
        // Good frames are not recorded as corrective feedback.
        assertTrue(snapshot.feedbackMessages.isEmpty())
    }

    @Test
    fun snapshotBeforeAnyFrame_hasNoScoresOrLiveScore() {
        val evaluator = evaluator(listOf(0L to samplePose()))

        val snapshot = evaluator.snapshot(includeSeries = true)

        assertEquals(0, snapshot.frameCount)
        assertEquals(0.0, snapshot.alignmentScore, 1e-9)
        assertNull(snapshot.liveScore)
        assertNull(snapshot.latestFeedback)
        assertTrue(snapshot.timelinePoints.isEmpty())
        assertTrue(snapshot.performanceSegments.isEmpty())
    }

    @Test
    fun learnerPoseWithTooFewValues_isIgnored() {
        val evaluator = evaluator(listOf(0L to samplePose()))

        evaluator.onFrame(List(33 * 4 - 1) { 0.5f }, timeMs = 0L)

        assertEquals(0, evaluator.snapshot().frameCount)
    }

    @Test
    fun emptyTemplate_ignoresFrames() {
        val evaluator = PoseSimilarityEvaluator(
            template = PoseTemplate(fps = 10, frames = emptyList()),
            mirrorReferenceX = false
        )

        evaluator.onFrame(samplePose(), timeMs = 0L)

        assertEquals(0, evaluator.snapshot().frameCount)
    }

    @Test
    fun referenceFrameWithTooFewValues_isIgnored() {
        val evaluator = evaluator(listOf(0L to listOf(0.5f, 0.5f, 0f, 1f)))

        evaluator.onFrame(samplePose(), timeMs = 0L)

        assertEquals(0, evaluator.snapshot().frameCount)
    }

    // ------------------------------------------------------------
    // mirrorReferenceX
    // ------------------------------------------------------------

    @Test
    fun mirrorReferenceX_true_matchesLearnerWhoIsFacingTheOtherWay() {
        val reference = samplePose()
        val flippedLearner = flipX(reference)
        val evaluator = evaluator(listOf(0L to reference), mirrorReferenceX = true)

        evaluator.onFrame(flippedLearner, timeMs = 0L)
        val snapshot = evaluator.snapshot()

        assertEquals(1.0, snapshot.alignmentScore, 1e-6)
        assertEquals(0.0, snapshot.avgPoseError, 1e-6)
    }

    @Test
    fun mirrorReferenceX_false_penalisesAMirroredLearner() {
        val reference = samplePose()
        val flippedLearner = flipX(reference)
        val evaluator = evaluator(listOf(0L to reference), mirrorReferenceX = false)

        evaluator.onFrame(flippedLearner, timeMs = 0L)
        val snapshot = evaluator.snapshot()

        assertTrue("alignment=${snapshot.alignmentScore}", snapshot.alignmentScore < 0.9)
        assertTrue("error=${snapshot.avgPoseError}", snapshot.avgPoseError > 0.5)
        assertTrue(snapshot.latestFeedback?.message != "Nice match. Keep the timing steady.")
    }

    @Test
    fun mirrorReferenceX_true_penalisesAnUnflippedLearner() {
        val reference = samplePose()
        val evaluator = evaluator(listOf(0L to reference), mirrorReferenceX = true)

        evaluator.onFrame(reference, timeMs = 0L)

        assertTrue(evaluator.snapshot().alignmentScore < 0.9)
    }

    // ------------------------------------------------------------
    // Body areas / limbs word feedback
    // ------------------------------------------------------------

    @Test
    fun feedback_rightArmMovedUp_saysLowerYourRightArm() {
        // Existing behaviour: a negative dy (learner higher in the image) yields "Lower".
        val learner = samplePose()
        listOf(14, 16).forEach { shift(learner, it, dy = -0.30f) }

        val feedback = feedbackFor(learner)

        assertEquals("Lower your right arm.", feedback?.message)
        assertEquals("right arm", feedback?.focusArea)
    }

    @Test
    fun feedback_leftLegMovedDown_saysLiftYourLeftLeg() {
        val learner = samplePose()
        listOf(25, 27).forEach { shift(learner, it, dy = 0.30f) }

        val feedback = feedbackFor(learner)

        assertEquals("Lift your left leg.", feedback?.message)
        assertEquals("left leg", feedback?.focusArea)
    }

    @Test
    fun feedback_rightLegMovedUp_saysLowerYourRightLeg() {
        val learner = samplePose()
        listOf(26, 28).forEach { shift(learner, it, dy = -0.30f) }

        val feedback = feedbackFor(learner)

        assertEquals("Lower your right leg.", feedback?.message)
        assertEquals("right leg", feedback?.focusArea)
    }

    @Test
    fun feedback_leftArmShiftedHorizontallyPositive_saysMoveLeft() {
        val learner = samplePose()
        listOf(13, 15).forEach { shift(learner, it, dx = 0.30f) }

        val feedback = feedbackFor(learner)

        assertEquals("Move your left arm left.", feedback?.message)
        assertEquals("left arm", feedback?.focusArea)
    }

    @Test
    fun feedback_leftArmShiftedHorizontallyNegative_saysMoveRight() {
        val learner = samplePose()
        listOf(13, 15).forEach { shift(learner, it, dx = -0.30f) }

        val feedback = feedbackFor(learner)

        assertEquals("Move your left arm right.", feedback?.message)
    }

    @Test
    fun feedback_areaWithLargestMismatchWins() {
        val learner = samplePose()
        // Left arm off by 1.5 body-widths, right leg only by 0.5 (both above the joint threshold).
        listOf(13, 15).forEach { shift(learner, it, dy = 0.30f) }
        listOf(26, 28).forEach { shift(learner, it, dy = 0.10f) }

        val feedback = feedbackFor(learner)

        assertEquals("left arm", feedback?.focusArea)
        assertEquals("Lift your left arm.", feedback?.message)
    }

    @Test
    fun feedback_smallErrorsBelowJointThreshold_askToResetPosture() {
        // Elbow and wrist 0.05 lower: ~0.25 normalised distance (< 0.28 per-joint threshold)
        // but the score (~0.999) is below goodThreshold = 1.0, so corrective feedback is built.
        val learner = samplePose()
        listOf(14, 16).forEach { shift(learner, it, dy = 0.05f) }

        val feedback = feedbackFor(learner, goodThreshold = 1.0)

        assertEquals("Reset your posture and match the reference shape.", feedback?.message)
        assertEquals("overall", feedback?.focusArea)
    }

    @Test
    fun feedbackMessages_recordedOncePerDistinctMessage() {
        val reference = samplePose()
        val learner = samplePose()
        listOf(13, 15).forEach { shift(learner, it, dy = 0.30f) }
        val evaluator = evaluator(
            listOf(0L to reference, 1_000L to reference, 2_000L to reference),
            fps = 1,
            goodThreshold = 0.99
        )

        evaluator.onFrame(learner, 0L)
        evaluator.onFrame(learner, 1_000L)
        evaluator.onFrame(learner, 2_000L)
        val snapshot = evaluator.snapshot()

        assertEquals(3, snapshot.frameCount)
        assertEquals(1, snapshot.feedbackMessages.size)
        assertEquals(0L, snapshot.feedbackMessages.first().tMs)
        assertEquals("Lift your left arm.", snapshot.feedbackMessages.first().message)
    }

    // ------------------------------------------------------------
    // goodThreshold
    // ------------------------------------------------------------

    @Test
    fun goodThreshold_lowerThanScore_givesNiceMatch_higherGivesCorrection() {
        val learner = samplePose()
        listOf(14, 16).forEach { shift(learner, it, dy = 0.05f) } // score ~0.9988

        val lenient = feedbackFor(learner, goodThreshold = 0.99)
        val strict = feedbackFor(learner, goodThreshold = 1.0)

        assertEquals("Nice match. Keep the timing steady.", lenient?.message)
        assertEquals("Reset your posture and match the reference shape.", strict?.message)
    }

    // ------------------------------------------------------------
    // Visibility handling
    // ------------------------------------------------------------

    @Test
    fun lowVisibilityJoint_isExcludedFromTheError() {
        val learner = samplePose()
        shift(learner, 15, dy = 0.40f)
        learner[15 * 4 + 3] = 0.1f // below minVisibility (0.3)
        val evaluator = evaluator(listOf(0L to samplePose()))

        evaluator.onFrame(learner, 0L)
        val snapshot = evaluator.snapshot()

        assertEquals(0.0, snapshot.avgPoseError, 1e-9)
        assertEquals(1.0, snapshot.alignmentScore, 1e-9)
    }

    @Test
    fun noVisibleJoints_usesMaximumErrorOfOne_andResetsPostureFeedback() {
        val learner = samplePose()
        hideStableJoints(learner)
        val evaluator = evaluator(listOf(0L to samplePose()))

        evaluator.onFrame(learner, 0L)
        val snapshot = evaluator.snapshot()

        assertEquals(1.0, snapshot.avgPoseError, 1e-9)
        assertTrue("score=${snapshot.alignmentScore}", snapshot.alignmentScore in 0.4..0.6)
        assertEquals("Reset your posture and match the reference shape.", snapshot.latestFeedback?.message)
    }

    // ------------------------------------------------------------
    // Series: timeline, segments, consistency, smoothness, live score
    // ------------------------------------------------------------

    @Test
    fun timeline_isDownsampledToTheSampleInterval() {
        val reference = samplePose()
        val evaluator = evaluator(
            listOf(0L to reference, 100L to reference, 200L to reference, 300L to reference),
            fps = 10
        )

        listOf(0L, 100L, 200L, 300L).forEach { evaluator.onFrame(reference, it) }
        val snapshot = evaluator.snapshot(includeSeries = true)

        assertEquals(4, snapshot.frameCount)
        // 250 ms between samples: t=0 is kept, 100/200 are skipped, 300 is kept.
        assertEquals(listOf(0L, 300L), snapshot.timelinePoints.map { it.tMs })
        assertTrue(snapshot.timelinePoints.all { it.score > 0.999 && it.err < 1e-6 })
    }

    @Test
    fun snapshotWithoutSeries_leavesTimelineAndSegmentsEmpty() {
        val reference = samplePose()
        val evaluator = evaluator(listOf(0L to reference, 1_000L to reference), fps = 1)

        evaluator.onFrame(reference, 0L)
        evaluator.onFrame(reference, 1_000L)
        val snapshot = evaluator.snapshot(includeSeries = false)

        assertTrue(snapshot.timelinePoints.isEmpty())
        assertTrue(snapshot.performanceSegments.isEmpty())
        assertNotNull(snapshot.liveScore)
    }

    @Test
    fun performanceSegments_splitWhenTheLabelChanges() {
        val reference = samplePose()
        val bad = samplePose()
        hideStableJoints(bad) // error 1.0 -> score ~0.5
        val evaluator = PoseSimilarityEvaluator(
            template = template(
                listOf(0L to reference, 1_000L to reference, 2_000L to reference),
                fps = 1
            ),
            mirrorReferenceX = false,
            goodThreshold = 0.9,
            okThreshold = 0.6
        )

        evaluator.onFrame(reference, 0L)
        evaluator.onFrame(bad, 1_000L)
        evaluator.onFrame(bad, 2_000L)
        val segments = evaluator.snapshot(includeSeries = true).performanceSegments

        assertEquals(2, segments.size)
        assertEquals(SegmentLabel.GOOD, segments[0].label)
        assertEquals(0L, segments[0].startMs)
        assertEquals(1_000L, segments[0].endMs)
        assertEquals(1.0, segments[0].avgScore, 1e-9)
        assertEquals(SegmentLabel.BAD, segments[1].label)
        assertEquals(1_000L, segments[1].startMs)
        assertEquals(2_000L, segments[1].endMs)
        assertEquals(1.0, segments[1].avgErr, 1e-9)
    }

    @Test
    fun performanceSegments_okLabelBetweenThresholds() {
        val reference = samplePose()
        val off = samplePose()
        hideStableJoints(off) // score ~0.5: OK with the default thresholds (>= 0.45, < 0.65)
        val evaluator = evaluator(listOf(0L to reference, 1_000L to reference), fps = 1)

        evaluator.onFrame(off, 0L)
        evaluator.onFrame(off, 1_000L)
        val segments = evaluator.snapshot(includeSeries = true).performanceSegments

        assertEquals(1, segments.size)
        assertEquals(SegmentLabel.OK, segments.single().label)
    }

    @Test
    fun steadyPerfectPerformance_hasFullConsistencyAndSmoothness() {
        val reference = samplePose()
        val evaluator = evaluator(
            listOf(0L to reference, 1_000L to reference, 2_000L to reference),
            fps = 1
        )

        listOf(0L, 1_000L, 2_000L).forEach { evaluator.onFrame(reference, it) }
        val snapshot = evaluator.snapshot()

        assertEquals(1.0, snapshot.timingConsistencyScore, 1e-9)
        assertEquals(1.0, snapshot.smoothnessScore, 1e-9)
    }

    @Test
    fun alternatingGoodAndBadFrames_lowersConsistencyAndSmoothnessToZero() {
        val reference = samplePose()
        val bad = samplePose()
        hideStableJoints(bad)
        val times = listOf(0L, 1_000L, 2_000L, 3_000L)
        val evaluator = evaluator(times.map { it to reference }, fps = 1)

        evaluator.onFrame(reference, times[0])
        evaluator.onFrame(bad, times[1])
        evaluator.onFrame(reference, times[2])
        evaluator.onFrame(bad, times[3])
        val snapshot = evaluator.snapshot()

        assertEquals(4, snapshot.frameCount)
        assertEquals(0.0, snapshot.timingConsistencyScore, 1e-9)
        assertEquals(0.0, snapshot.smoothnessScore, 1e-9)
        assertEquals(0.5, snapshot.avgPoseError, 1e-9)
    }

    @Test
    fun liveScore_reactsSlowerThanTheOverallAverageAfterABadFrame() {
        val reference = samplePose()
        val bad = samplePose()
        hideStableJoints(bad)
        val evaluator = evaluator(listOf(0L to reference, 1_000L to reference), fps = 1)

        evaluator.onFrame(reference, 0L)
        evaluator.onFrame(bad, 1_000L)
        val snapshot = evaluator.snapshot()

        // EWMA with beta 0.08 stays near 1.0 while the plain mean is roughly (1 + 0.5) / 2.
        assertTrue("live=${snapshot.liveScore}", snapshot.liveScore!! > 0.9)
        assertTrue(snapshot.liveScore!! > snapshot.alignmentScore)
    }

    @Test
    fun templateFramesGivenOutOfOrder_areSortedByTime() {
        val reference = samplePose()
        val unsorted = listOf(2_000L to reference, 0L to reference, 1_000L to reference)
        val evaluator = evaluator(unsorted, fps = 1)

        listOf(0L, 1_000L, 2_000L).forEach { evaluator.onFrame(reference, it) }

        assertEquals(1.0, evaluator.snapshot().alignmentScore, 1e-9)
        assertEquals(3, evaluator.snapshot().frameCount)
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private fun template(frames: List<Pair<Long, List<Float>>>, fps: Int) = PoseTemplate(
        fps = fps,
        frames = frames.map { (t, landmarks) -> PoseFrame(tMs = t, landmarks = landmarks) }
    )

    private fun evaluator(
        frames: List<Pair<Long, List<Float>>>,
        mirrorReferenceX: Boolean = false,
        fps: Int = 30,
        goodThreshold: Double = 0.65
    ) = PoseSimilarityEvaluator(
        template = template(frames, fps),
        mirrorReferenceX = mirrorReferenceX,
        goodThreshold = goodThreshold
    )

    private fun feedbackFor(
        learner: List<Float>,
        goodThreshold: Double = 0.99
    ): PoseWordFeedback? {
        val evaluator = PoseSimilarityEvaluator(
            template = template(listOf(0L to samplePose()), fps = 30),
            mirrorReferenceX = false,
            goodThreshold = goodThreshold
        )
        evaluator.onFrame(learner, 0L)
        return evaluator.snapshot().latestFeedback
    }

    private fun samplePose(): MutableList<Float> {
        val landmarks = MutableList(33 * 4) { 0f }
        for (idx in 0 until 33) {
            landmarks[idx * 4] = 0.5f
            landmarks[idx * 4 + 1] = 0.5f
            landmarks[idx * 4 + 2] = 0f
            landmarks[idx * 4 + 3] = 1f
        }

        setLandmark(landmarks, 11, x = 0.40f, y = 0.30f)
        setLandmark(landmarks, 12, x = 0.60f, y = 0.30f)
        setLandmark(landmarks, 13, x = 0.35f, y = 0.45f)
        setLandmark(landmarks, 14, x = 0.65f, y = 0.45f)
        setLandmark(landmarks, 15, x = 0.32f, y = 0.58f)
        setLandmark(landmarks, 16, x = 0.68f, y = 0.58f)
        setLandmark(landmarks, 23, x = 0.45f, y = 0.60f)
        setLandmark(landmarks, 24, x = 0.55f, y = 0.60f)
        setLandmark(landmarks, 25, x = 0.44f, y = 0.78f)
        setLandmark(landmarks, 26, x = 0.56f, y = 0.78f)
        setLandmark(landmarks, 27, x = 0.43f, y = 0.92f)
        setLandmark(landmarks, 28, x = 0.57f, y = 0.92f)

        return landmarks
    }

    private fun setLandmark(landmarks: MutableList<Float>, idx: Int, x: Float, y: Float) {
        landmarks[idx * 4] = x
        landmarks[idx * 4 + 1] = y
    }

    private fun shift(landmarks: MutableList<Float>, idx: Int, dx: Float = 0f, dy: Float = 0f) {
        landmarks[idx * 4] += dx
        landmarks[idx * 4 + 1] += dy
    }

    /** Reflects every landmark horizontally around x = 0.5. */
    private fun flipX(landmarks: List<Float>): List<Float> {
        val out = landmarks.toMutableList()
        for (idx in 0 until 33) {
            out[idx * 4] = 1f - landmarks[idx * 4]
        }
        return out
    }

    /** Makes every joint the evaluator scores invisible (visibility below minVisibility). */
    private fun hideStableJoints(landmarks: MutableList<Float>) {
        listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28).forEach { idx ->
            landmarks[idx * 4 + 3] = 0.1f
        }
    }
}
