package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSimilarityEvaluatorTest {

    @Test
    fun noReferenceHandData_usesBodyOnlyFallback() {
        val evaluator = HandSimilarityEvaluator(template = PoseTemplate(fps = 30))

        evaluator.onFrame(
            learnerLeftHand = handLandmarks(),
            learnerRightHand = handLandmarks(xOffset = 0.4f),
            timeMs = 0L
        )

        val snapshot = evaluator.snapshot()
        assertEquals(false, snapshot.hasReferenceHands)
        assertNull(snapshot.handScore)
        assertEquals(
            0.8,
            HandSimilarityEvaluator.combineWithBodyScore(
                bodyScore = 0.8,
                handScore = snapshot.handScore,
                hasReferenceHands = snapshot.hasReferenceHands
            ),
            0.000001
        )
    }

    @Test
    fun missingLearnerHand_returnsNoHandScoreAndVisibilityFeedback() {
        val evaluator = HandSimilarityEvaluator(
            template = templateWithHands(leftHand = handLandmarks())
        )

        evaluator.onFrame(
            learnerLeftHand = emptyList(),
            learnerRightHand = emptyList(),
            timeMs = 0L
        )

        val snapshot = evaluator.snapshot()
        assertTrue(snapshot.hasReferenceHands)
        assertNull(snapshot.handScore)
        assertEquals("Left hand not detected.", snapshot.latestFeedback)
        assertEquals(0.8, HandSimilarityEvaluator.combineWithBodyScore(0.8, snapshot.handScore, true), 0.000001)
    }

    @Test
    fun missingReferenceHand_returnsNoHandScore() {
        val evaluator = HandSimilarityEvaluator(
            template = templateWithHands(leftHand = emptyList(), rightHand = emptyList())
        )

        evaluator.onFrame(
            learnerLeftHand = handLandmarks(),
            learnerRightHand = handLandmarks(xOffset = 0.4f),
            timeMs = 0L
        )

        val snapshot = evaluator.snapshot()
        assertEquals(false, snapshot.hasReferenceHands)
        assertNull(snapshot.handScore)
    }

    @Test
    fun leftHandOnly_scoresLeftHand() {
        val left = handLandmarks()
        val evaluator = HandSimilarityEvaluator(template = templateWithHands(leftHand = left))

        evaluator.onFrame(learnerLeftHand = left.translated(0.2f, 0.1f), learnerRightHand = emptyList(), timeMs = 0L)

        val snapshot = evaluator.snapshot()
        assertNotNull(snapshot.handScore)
        assertEquals(snapshot.handScore!!, snapshot.leftHandScore!!, 0.000001)
        assertNull(snapshot.rightHandScore)
        assertTrue(snapshot.handScore!! > 0.99)
    }

    @Test
    fun rightHandOnly_scoresRightHand() {
        val right = handLandmarks(xOffset = 0.4f)
        val evaluator = HandSimilarityEvaluator(template = templateWithHands(rightHand = right))

        evaluator.onFrame(learnerLeftHand = emptyList(), learnerRightHand = right.scaledFromWrist(1.8f), timeMs = 0L)

        val snapshot = evaluator.snapshot()
        assertNotNull(snapshot.handScore)
        assertNull(snapshot.leftHandScore)
        assertEquals(snapshot.handScore!!, snapshot.rightHandScore!!, 0.000001)
        assertTrue(snapshot.handScore!! > 0.99)
    }

    @Test
    fun bothHands_averagesAvailableHandScores() {
        val left = handLandmarks()
        val right = handLandmarks(xOffset = 0.4f)
        val evaluator = HandSimilarityEvaluator(
            template = templateWithHands(leftHand = left, rightHand = right)
        )

        val mismatchedRight = right.toMutableList().also {
            it[8 * LANDMARK_VALUE_COUNT] += 0.2f
            it[12 * LANDMARK_VALUE_COUNT + 1] += 0.2f
        }
        evaluator.onFrame(learnerLeftHand = left, learnerRightHand = mismatchedRight, timeMs = 0L)

        val snapshot = evaluator.snapshot()
        val expected = (snapshot.leftHandScore!! + snapshot.rightHandScore!!) / 2.0
        assertEquals(expected, snapshot.handScore!!, 0.000001)
        assertTrue(snapshot.leftHandScore!! > snapshot.rightHandScore!!)
    }

    @Test
    fun weightedBodyAndHandScore_usesFifteenPercentHands() {
        val combined = HandSimilarityEvaluator.combineWithBodyScore(
            bodyScore = 0.8,
            handScore = 0.4,
            hasReferenceHands = true
        )

        assertEquals(0.74, combined, 0.000001)
    }

    @Test
    fun malformedShortArrays_doNotCrashAndReturnNoScore() {
        val evaluator = HandSimilarityEvaluator(
            template = templateWithHands(leftHand = listOf(0f, 1f, 2f))
        )

        evaluator.onFrame(
            learnerLeftHand = listOf(0f, 1f),
            learnerRightHand = listOf(0f),
            timeMs = 0L
        )

        val snapshot = evaluator.snapshot()
        assertNull(snapshot.handScore)
        assertEquals(false, snapshot.hasReferenceHands)
    }

    private fun templateWithHands(
        leftHand: List<Float> = emptyList(),
        rightHand: List<Float> = emptyList()
    ): PoseTemplate {
        return PoseTemplate(
            fps = 30,
            frames = listOf(PoseFrame(tMs = 0L, landmarks = List(33 * 4) { 1f })),
            defaultTrackId = "track_1",
            handTracks = listOf(
                HandTrack(
                    trackId = "track_1",
                    frames = listOf(
                        HandLandmarkFrame(
                            tMs = 0L,
                            leftHand = leftHand,
                            rightHand = rightHand
                        )
                    )
                )
            )
        )
    }

    private fun handLandmarks(
        xOffset: Float = 0f,
        yOffset: Float = 0f,
        spread: Float = 1f
    ): List<Float> {
        val points = List(HAND_LANDMARK_COUNT) { idx ->
            val finger = idx / 4
            val joint = idx % 4
            val x = xOffset + (finger * 0.025f + joint * 0.006f) * spread
            val y = yOffset + (joint * 0.035f + finger * 0.004f) * spread
            listOf(x, y, 0f, 1f)
        }.flatten().toMutableList()
        points[0] = xOffset
        points[1] = yOffset
        return points
    }

    private fun List<Float>.translated(dx: Float, dy: Float): List<Float> {
        return toMutableList().also { out ->
            for (idx in 0 until size / LANDMARK_VALUE_COUNT) {
                val base = idx * LANDMARK_VALUE_COUNT
                out[base] += dx
                out[base + 1] += dy
            }
        }
    }

    private fun List<Float>.scaledFromWrist(scale: Float): List<Float> {
        val wristX = this[0]
        val wristY = this[1]
        return toMutableList().also { out ->
            for (idx in 1 until size / LANDMARK_VALUE_COUNT) {
                val base = idx * LANDMARK_VALUE_COUNT
                out[base] = wristX + (out[base] - wristX) * scale
                out[base + 1] = wristY + (out[base + 1] - wristY) * scale
            }
        }
    }
}
