package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingLandmarkInterpolatorTest {

    @Test
    fun interpolate_fillsShortGapWithLowVisibilityLandmarks() {
        val track = PoseTrack(
            trackId = "track_1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.20f, 0.20f, 0.38f, 0.72f, z = -0.1f)),
                PoseFrame(tMs = 250L, landmarks = samplePose(0.30f, 0.30f, 0.48f, 0.82f, z = 0.1f))
            ),
            avgVisibility = 0.8f
        )

        val result = MissingLandmarkInterpolator(fps = 8).interpolate(listOf(track))

        assertEquals(1, result.interpolatedFrameCount)
        val frames = result.tracks.first().frames
        assertEquals(listOf(0L, 125L, 250L), frames.map { it.tMs })
        assertEquals(0.25f, frames[1].landmarks[0], 0.0001f)
        assertEquals(0.25f, frames[1].landmarks[1], 0.0001f)
        assertEquals(0.0f, frames[1].landmarks[2], 0.0001f)
        assertTrue(frames[1].landmarks.averageVisibility() <= 0.20f)
    }

    @Test
    fun interpolate_doesNotFillLongGap() {
        val track = PoseTrack(
            trackId = "track_1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.20f, 0.20f, 0.38f, 0.72f)),
                PoseFrame(tMs = 1_000L, landmarks = samplePose(0.30f, 0.30f, 0.48f, 0.82f))
            ),
            avgVisibility = 0.8f
        )

        val result = MissingLandmarkInterpolator(fps = 8).interpolate(listOf(track))

        assertEquals(0, result.interpolatedFrameCount)
        assertEquals(listOf(0L, 1_000L), result.tracks.first().frames.map { it.tMs })
    }

    @Test
    fun interpolate_doesNotFillFromLowConfidenceHiddenEndpoints() {
        val track = PoseTrack(
            trackId = "track_1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.20f, 0.20f, 0.38f, 0.72f, visibility = 0.05f)),
                PoseFrame(tMs = 250L, landmarks = samplePose(0.30f, 0.30f, 0.48f, 0.82f, visibility = 0.8f))
            ),
            avgVisibility = 0.4f
        )

        val result = MissingLandmarkInterpolator(fps = 8).interpolate(listOf(track))

        assertEquals(0, result.interpolatedFrameCount)
        assertEquals(2, result.tracks.first().frames.size)
    }

    private fun samplePose(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        visibility: Float = 0.8f,
        z: Float = 0f
    ): List<Float> {
        return listOf(
            left, top, z, visibility,
            right, top, z, visibility,
            left, bottom, z, visibility,
            right, bottom, z, visibility
        )
    }
}
