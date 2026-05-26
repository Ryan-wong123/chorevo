package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSimilarityEvaluatorTest {

    @Test
    fun snapshot_includesWordFeedbackForLargestPoseMismatch() {
        val reference = samplePose()
        val learner = samplePose().toMutableList()
        listOf(11, 13, 15).forEach { idx ->
            learner[idx * 4 + 1] += 0.20f
        }

        val evaluator = PoseSimilarityEvaluator(
            template = PoseTemplate(
                fps = 30,
                frames = listOf(PoseFrame(tMs = 0L, landmarks = reference))
            ),
            mirrorReferenceX = false,
            goodThreshold = 0.99
        )

        evaluator.onFrame(learner, timeMs = 0L)

        val snapshot = evaluator.snapshot(includeSeries = true)

        assertEquals("Lift your left arm.", snapshot.latestFeedback?.message)
        assertTrue(snapshot.feedbackMessages.any { it.tMs == 0L && it.message == "Lift your left arm." })
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
}
