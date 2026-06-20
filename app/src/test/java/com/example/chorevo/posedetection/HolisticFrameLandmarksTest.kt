package com.example.chorevo.posedetection

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HolisticFrameLandmarksTest {

    @Test
    fun flattenPoseLandmarks_preservesPoseValues() {
        val landmarks = normalizedLandmarks(POSE_LANDMARK_COUNT, seed = 0.10f, visibility = 0.80f)

        val flattened = landmarks.flattenPoseLandmarks()

        assertEquals(POSE_LANDMARK_COUNT * LANDMARK_VALUE_COUNT, flattened.size)
        assertEquals(0.10f, flattened[0], EPSILON)
        assertEquals(0.20f, flattened[1], EPSILON)
        assertEquals(-0.10f, flattened[2], EPSILON)
        assertEquals(0.80f, flattened[3], EPSILON)
        assertEquals(0.42f, flattened[32 * LANDMARK_VALUE_COUNT], EPSILON)
        assertEquals(0.52f, flattened[32 * LANDMARK_VALUE_COUNT + 1], EPSILON)
    }

    @Test
    fun flattenHandLandmarks_preservesLeftAndRightHandValues() {
        val leftHand = normalizedLandmarks(HAND_LANDMARK_COUNT, seed = 0.25f, visibility = 0.70f)
            .flattenHandLandmarks()
        val rightHand = normalizedLandmarks(HAND_LANDMARK_COUNT, seed = 0.55f, visibility = 0.60f)
            .flattenHandLandmarks()

        val frame = HolisticFrameLandmarks(
            pose = normalizedLandmarks(POSE_LANDMARK_COUNT, seed = 0.05f).flattenPoseLandmarks(),
            leftHand = leftHand,
            rightHand = rightHand
        )

        assertEquals(HAND_LANDMARK_COUNT * LANDMARK_VALUE_COUNT, frame.leftHand.size)
        assertEquals(HAND_LANDMARK_COUNT * LANDMARK_VALUE_COUNT, frame.rightHand.size)
        assertEquals(0.25f, frame.leftHand[0], EPSILON)
        assertEquals(0.45f, frame.leftHand[20 * LANDMARK_VALUE_COUNT], EPSILON)
        assertEquals(0.55f, frame.rightHand[0], EPSILON)
        assertEquals(0.75f, frame.rightHand[20 * LANDMARK_VALUE_COUNT], EPSILON)
    }

    @Test
    fun flattenLandmarks_missingOrShortHandsReturnEmptyLists() {
        assertTrue(emptyList<NormalizedLandmark>().flattenHandLandmarks().isEmpty())
        assertTrue(normalizedLandmarks(HAND_LANDMARK_COUNT - 1).flattenHandLandmarks().isEmpty())
        assertTrue(normalizedLandmarks(POSE_LANDMARK_COUNT - 1).flattenPoseLandmarks().isEmpty())
    }

    @Test
    fun holisticFrameLandmarks_exposesPoseAndHandsOnlyWithoutFaceData() {
        val fieldNames = HolisticFrameLandmarks::class.java.declaredFields
            .map { it.name.lowercase() }

        assertTrue("pose" in fieldNames)
        assertTrue("lefthand" in fieldNames)
        assertTrue("righthand" in fieldNames)
        assertFalse(fieldNames.any { it.contains("face") })
    }

    private fun normalizedLandmarks(
        count: Int,
        seed: Float = 0.10f,
        visibility: Float = 1.0f
    ): List<NormalizedLandmark> {
        return List(count) { index ->
            NormalizedLandmark.create(
                seed + index * 0.01f,
                seed + 0.10f + index * 0.01f,
                -seed,
                Optional.of(visibility),
                Optional.empty()
            )
        }
    }

    private companion object {
        const val EPSILON = 0.000001f
    }
}
