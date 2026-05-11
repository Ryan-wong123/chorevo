package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseDetectionMergerTest {

    @Test
    fun mergeDuplicateLandmarkDetections_keepsHigherPriorityOverlap() {
        val lowConfidenceDuplicate = samplePose(
            left = 0.10f,
            top = 0.20f,
            right = 0.30f,
            bottom = 0.70f,
            visibility = 0.30f
        )
        val highConfidenceOriginal = samplePose(
            left = 0.11f,
            top = 0.21f,
            right = 0.31f,
            bottom = 0.71f,
            visibility = 0.90f
        )
        val separateDancer = samplePose(
            left = 0.60f,
            top = 0.18f,
            right = 0.82f,
            bottom = 0.72f,
            visibility = 0.80f
        )

        val merged = PoseDetectionMerger.mergeDuplicateLandmarkDetections(
            listOf(lowConfidenceDuplicate, separateDancer, highConfidenceOriginal)
        )

        assertEquals(listOf(highConfidenceOriginal, separateDancer), merged)
    }

    @Test
    fun mergeDuplicateDetections_returnsEmptyForNoOpDetectorFallbackData() {
        val merged = PoseDetectionMerger.mergeDuplicateDetections(emptyList())

        assertEquals(emptyList<DetectionBox>(), merged)
    }

    private fun samplePose(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        visibility: Float
    ): List<Float> {
        return listOf(
            left, top, 0f, visibility,
            right, top, 0f, visibility,
            left, bottom, 0f, visibility,
            right, bottom, 0f, visibility
        )
    }
}
