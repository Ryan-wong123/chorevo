package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFragmentMergerTest {

    @Test
    fun merge_combinesPlausibleSplitTrackFragments() {
        val first = PoseTrack(
            trackId = "track_1",
            label = "Dancer 1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.20f, 0.20f, 0.38f, 0.72f)),
                PoseFrame(tMs = 125L, landmarks = samplePose(0.22f, 0.20f, 0.40f, 0.72f))
            ),
            avgVisibility = 0.8f
        )
        val second = PoseTrack(
            trackId = "track_2",
            label = "Dancer 2",
            frames = listOf(
                PoseFrame(tMs = 500L, landmarks = samplePose(0.27f, 0.21f, 0.45f, 0.73f)),
                PoseFrame(tMs = 625L, landmarks = samplePose(0.29f, 0.21f, 0.47f, 0.73f))
            ),
            avgVisibility = 0.8f
        )

        val result = TrackFragmentMerger(fps = 8).merge(listOf(first, second))

        assertEquals(1, result.tracks.size)
        assertEquals(4, result.tracks.first().frames.size)
        assertEquals(mapOf("track_2" to "track_1"), result.replacedTrackIds)
    }

    @Test
    fun merge_keepsOverlappingRealDancersSeparate() {
        val first = PoseTrack(
            trackId = "track_1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.12f, 0.20f, 0.30f, 0.72f)),
                PoseFrame(tMs = 125L, landmarks = samplePose(0.14f, 0.20f, 0.32f, 0.72f)),
                PoseFrame(tMs = 250L, landmarks = samplePose(0.16f, 0.20f, 0.34f, 0.72f))
            ),
            avgVisibility = 0.8f
        )
        val second = PoseTrack(
            trackId = "track_2",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.62f, 0.20f, 0.80f, 0.72f)),
                PoseFrame(tMs = 125L, landmarks = samplePose(0.60f, 0.20f, 0.78f, 0.72f)),
                PoseFrame(tMs = 250L, landmarks = samplePose(0.58f, 0.20f, 0.76f, 0.72f))
            ),
            avgVisibility = 0.8f
        )

        val result = TrackFragmentMerger(fps = 8).merge(listOf(first, second))

        assertEquals(2, result.tracks.size)
        assertEquals(emptyMap<String, String>(), result.replacedTrackIds)
    }

    @Test
    fun merge_combinesSignificantOverlapOnlyForClearDuplicates() {
        val first = PoseTrack(
            trackId = "track_1",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.20f, 0.20f, 0.38f, 0.72f, visibility = 0.6f)),
                PoseFrame(tMs = 125L, landmarks = samplePose(0.22f, 0.20f, 0.40f, 0.72f, visibility = 0.6f))
            ),
            avgVisibility = 0.6f
        )
        val duplicate = PoseTrack(
            trackId = "track_2",
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = samplePose(0.205f, 0.205f, 0.385f, 0.725f, visibility = 0.9f)),
                PoseFrame(tMs = 125L, landmarks = samplePose(0.225f, 0.205f, 0.405f, 0.725f, visibility = 0.9f))
            ),
            avgVisibility = 0.9f
        )

        val result = TrackFragmentMerger(fps = 8).merge(listOf(first, duplicate))

        assertEquals(1, result.tracks.size)
        assertEquals("track_2", result.tracks.first().trackId)
        assertEquals(2, result.tracks.first().frames.size)
    }

    private fun samplePose(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        visibility: Float = 0.8f
    ): List<Float> {
        return listOf(
            left, top, 0f, visibility,
            right, top, 0f, visibility,
            left, bottom, 0f, visibility,
            right, bottom, 0f, visibility
        )
    }
}
