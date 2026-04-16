package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPersonPoseTrackerTest {

    @Test
    fun buildTemplate_tracksMultipleDancers_andCreatesSharedPreview() {
        val tracker = MultiPersonPoseTracker(
            fps = 15,
            minTrackFrames = 1
        )

        tracker.addFrame(
            tMs = 0L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.12f, top = 0.18f, right = 0.28f, bottom = 0.62f),
                samplePose(left = 0.62f, top = 0.16f, right = 0.80f, bottom = 0.64f)
            )
        )

        tracker.addFrame(
            tMs = 67L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.14f, top = 0.20f, right = 0.30f, bottom = 0.64f),
                samplePose(left = 0.60f, top = 0.18f, right = 0.78f, bottom = 0.66f)
            )
        )

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!

        assertEquals(2, template.tracks.size)
        assertEquals("track_1", template.defaultTrackId)
        assertEquals(listOf("Dancer 1", "Dancer 2"), template.tracks.map { it.label })
        assertEquals(listOf(2, 2), template.tracks.map { it.frames.size })

        val preview = template.selectionPreview
        assertNotNull(preview)
        assertEquals(2, preview?.candidates?.size)
        assertEquals(1080, preview?.frameWidth)
        assertEquals(1920, preview?.frameHeight)
    }

    @Test
    fun buildTemplate_filtersDuplicatePreviewCandidateForSameDancer() {
        val tracker = MultiPersonPoseTracker(
            fps = 15,
            minTrackFrames = 1
        )

        tracker.addFrame(
            tMs = 0L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.10f, top = 0.18f, right = 0.28f, bottom = 0.64f),
                samplePose(left = 0.60f, top = 0.18f, right = 0.80f, bottom = 0.66f),
                samplePose(left = 0.61f, top = 0.19f, right = 0.79f, bottom = 0.65f)
            )
        )

        tracker.addFrame(
            tMs = 67L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.12f, top = 0.20f, right = 0.30f, bottom = 0.66f),
                samplePose(left = 0.58f, top = 0.19f, right = 0.78f, bottom = 0.68f),
                samplePose(left = 0.59f, top = 0.20f, right = 0.77f, bottom = 0.67f)
            )
        )

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!

        assertEquals(2, template.tracks.size)
        assertEquals(2, template.selectionPreview?.candidates?.size)
    }

    @Test
    fun buildTemplate_dropsTinyFalsePositiveTrackFromSelector() {
        val tracker = MultiPersonPoseTracker(
            fps = 15,
            minTrackFrames = 1
        )

        tracker.addFrame(
            tMs = 0L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.10f, top = 0.18f, right = 0.28f, bottom = 0.64f),
                samplePose(left = 0.60f, top = 0.18f, right = 0.80f, bottom = 0.66f),
                samplePose(left = 0.88f, top = 0.08f, right = 0.91f, bottom = 0.16f)
            )
        )

        tracker.addFrame(
            tMs = 67L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.11f, top = 0.19f, right = 0.29f, bottom = 0.65f),
                samplePose(left = 0.59f, top = 0.19f, right = 0.79f, bottom = 0.67f)
            )
        )

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!

        assertEquals(2, template.tracks.size)
        assertEquals(2, template.selectionPreview?.candidates?.size)
        assertTrue(template.tracks.all { it.frames.isNotEmpty() })
    }

    @Test
    fun buildTemplate_keepsTracksThatAreNotInSharedPreviewFrame() {
        val tracker = MultiPersonPoseTracker(
            fps = 15,
            minTrackFrames = 1
        )

        tracker.addFrame(
            tMs = 0L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.10f, top = 0.18f, right = 0.28f, bottom = 0.64f),
                samplePose(left = 0.60f, top = 0.18f, right = 0.80f, bottom = 0.66f)
            )
        )

        tracker.addFrame(
            tMs = 67L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.11f, top = 0.19f, right = 0.29f, bottom = 0.65f),
                samplePose(left = 0.61f, top = 0.19f, right = 0.79f, bottom = 0.67f)
            )
        )

        tracker.addFrame(
            tMs = 134L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.46f, top = 0.30f, right = 0.56f, bottom = 0.52f)
            )
        )

        tracker.addFrame(
            tMs = 201L,
            width = 1080,
            height = 1920,
            detections = listOf(
                samplePose(left = 0.47f, top = 0.31f, right = 0.57f, bottom = 0.53f)
            )
        )

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!

        assertEquals(3, template.tracks.size)
        assertEquals(2, template.selectionPreview?.candidates?.size)
    }

    @Test
    fun availableTracks_createsLegacyTrackForSinglePoseTemplates() {
        val template = PoseTemplate(
            fps = 2,
            frames = listOf(
                PoseFrame(
                    tMs = 0L,
                    landmarks = samplePose(left = 0.2f, top = 0.1f, right = 0.4f, bottom = 0.7f)
                )
            )
        )

        val tracks = template.availableTracks()

        assertEquals(1, tracks.size)
        assertEquals(PoseTemplate.LEGACY_TRACK_ID, tracks.first().trackId)
        assertEquals("Dancer 1", tracks.first().label)
    }

    private fun samplePose(left: Float, top: Float, right: Float, bottom: Float): List<Float> {
        return listOf(
            left, top, 0f, 1f,
            right, top, 0f, 1f,
            left, bottom, 0f, 1f,
            right, bottom, 0f, 1f
        )
    }
}
