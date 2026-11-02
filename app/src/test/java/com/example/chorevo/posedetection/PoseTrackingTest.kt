package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extra tests for [MultiPersonPoseTracker] (PoseTracking.kt) and the pose helpers it relies on
 * (PoseBounds, poseBounds, averageVisibility, PoseTrackingConfig). Multi-dancer, duplicate
 * and fragment-merge scenarios are in MultiPersonPoseTrackerTest.
 */
class PoseTrackingTest {

    // ------------------------------------------------------------
    // buildTemplate: empty / rejected input
    // ------------------------------------------------------------

    @Test
    fun buildTemplate_withoutFrames_returnsNull() {
        val tracker = MultiPersonPoseTracker(fps = 10)

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun buildTemplate_onlyEmptyDetections_returnsNull() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        tracker.addFrame(0L, 1080, 1920, emptyList())
        tracker.addFrame(100L, 1080, 1920, emptyList())

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun addFrame_detectionWithNoVisibleLandmarks_isIgnored() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        // Visibility 0.05 is below the 0.15 bounds threshold, so no box can be built.
        val invisible = pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.05f)
        tracker.addFrame(0L, 1080, 1920, listOf(invisible))
        tracker.addFrame(100L, 1080, 1920, listOf(invisible))

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun addFrame_emptyLandmarkList_isIgnored() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        tracker.addFrame(0L, 1080, 1920, listOf(emptyList()))

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun buildTemplate_trackShorterThanMinTrackFrames_isDropped() {
        val tracker = MultiPersonPoseTracker(fps = 10) // default minTrackFrames = 3
        tracker.addFrame(0L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))
        tracker.addFrame(100L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun buildTemplate_lowAverageVisibility_isDropped() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1) // minAverageVisibility 0.35
        repeat(3) { i ->
            tracker.addFrame(i * 100L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.2f)))
        }

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun buildTemplate_tinyAverageArea_isDropped() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1) // minAverageArea 0.006
        repeat(3) { i ->
            tracker.addFrame(i * 100L, 1080, 1920, listOf(pose(0.50f, 0.50f, 0.52f, 0.52f)))
        }

        assertNull(tracker.buildTemplate())
    }

    @Test
    fun buildTemplate_lowTrackCoverage_isDropped() {
        // Seen at 0 ms and again 5 s later at 10 fps: 2 of ~51 expected frames (< 0.35).
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        tracker.addFrame(0L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))
        tracker.addFrame(5_000L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))

        assertNull(tracker.buildTemplate())
    }

    // ------------------------------------------------------------
    // buildTemplate: shape of the result
    // ------------------------------------------------------------

    @Test
    fun buildTemplate_singleDancer_populatesTemplateFields() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        tracker.addFrame(0L, 720, 1280, listOf(pose(0.20f, 0.20f, 0.50f, 0.80f, visibility = 0.9f)))
        tracker.addFrame(100L, 720, 1280, listOf(pose(0.21f, 0.20f, 0.51f, 0.80f, visibility = 0.9f)))
        tracker.addFrame(200L, 720, 1280, listOf(pose(0.22f, 0.21f, 0.52f, 0.81f, visibility = 0.9f)))

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!
        assertEquals(10, template.fps)
        assertEquals(1, template.tracks.size)
        val track = template.tracks.single()
        assertEquals("track_1", track.trackId)
        assertEquals("Dancer 1", track.label)
        assertEquals("track_1", template.defaultTrackId)
        assertEquals(listOf(0L, 100L, 200L), track.frames.map { it.tMs })
        assertEquals(track.frames, template.frames)
        assertEquals(0.9f, track.avgVisibility, 1e-4f)
        assertTrue(template.qualityWarnings.isEmpty())
        assertNotNull(template.selectionPreview)
        assertEquals(1, template.selectionPreview?.candidates?.size)
    }

    @Test
    fun buildTemplate_dancerSeenInMostFrames_becomesDancerOneEvenIfDetectedSecond() {
        val tracker = MultiPersonPoseTracker(fps = 15, minTrackFrames = 1)

        // Larger dancer B is seen first (track_1) but only in two frames; A is seen in three.
        tracker.addFrame(
            0L, 1080, 1920,
            listOf(
                pose(0.60f, 0.10f, 0.90f, 0.60f),   // B
                pose(0.10f, 0.20f, 0.25f, 0.60f)    // A
            )
        )
        tracker.addFrame(
            67L, 1080, 1920,
            listOf(
                pose(0.61f, 0.11f, 0.91f, 0.61f),
                pose(0.11f, 0.20f, 0.26f, 0.60f)
            )
        )
        tracker.addFrame(
            134L, 1080, 1920,
            listOf(pose(0.12f, 0.20f, 0.27f, 0.60f))
        )

        val template = tracker.buildTemplate()

        assertNotNull(template)
        template!!
        assertEquals(listOf("track_2", "track_1"), template.tracks.map { it.trackId })
        assertEquals(listOf("Dancer 1", "Dancer 2"), template.tracks.map { it.label })
        assertEquals("track_2", template.defaultTrackId)
        assertEquals(listOf(3, 2), template.tracks.map { it.frames.size })
        assertEquals(3, template.frames.size)
    }

    @Test
    fun buildTemplate_withoutFrameSize_reportsZeroPreviewSize() {
        val tracker = MultiPersonPoseTracker(fps = 10, minTrackFrames = 1)
        tracker.addFrame(0L, width = 0, height = 0, detections = listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))

        val preview = tracker.buildTemplate()?.selectionPreview

        assertNotNull(preview)
        assertEquals(0, preview?.frameWidth)
        assertEquals(0, preview?.frameHeight)
        assertEquals(0L, preview?.timeMs)
    }

    @Test
    fun buildTemplate_zeroFps_doesNotDivideByZero() {
        val tracker = MultiPersonPoseTracker(fps = 0, minTrackFrames = 1)
        tracker.addFrame(0L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))

        val template = tracker.buildTemplate()

        assertNotNull(template)
        assertEquals(0, template?.fps)
        assertEquals(1, template?.tracks?.size)
    }

    // ------------------------------------------------------------
    // Quality warnings
    // ------------------------------------------------------------

    @Test
    fun buildTemplate_lowConfidenceTrack_addsLowConfidenceWarning() {
        val tracker = MultiPersonPoseTracker(
            fps = 10,
            minTrackFrames = 1,
            minAverageVisibility = 0.1f
        )
        repeat(3) { i ->
            tracker.addFrame(i * 100L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.20f)))
        }

        val warnings = tracker.buildTemplate()?.qualityWarnings.orEmpty()

        assertEquals(listOf("lowConfidenceTracks"), warnings.map { it.code })
    }

    @Test
    fun buildTemplate_sparseTrack_addsManyMissingFramesWarning() {
        val tracker = MultiPersonPoseTracker(
            fps = 10,
            minTrackFrames = 1,
            minTrackCoverage = 0.05f
        )
        // 3 frames over 2.2 s at 10 fps: coverage ~0.13 (< 0.20 warning, >= 0.05 kept).
        listOf(0L, 2_100L, 2_200L).forEach { t ->
            tracker.addFrame(t, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f)))
        }

        val template = tracker.buildTemplate()

        assertNotNull(template)
        assertEquals(listOf("manyMissingFrames"), template!!.qualityWarnings.map { it.code })
    }

    // ------------------------------------------------------------
    // Config-based constructor
    // ------------------------------------------------------------

    @Test
    fun standardConfig_keepsStableTrack_butRejectsWeakVisibilityTrack() {
        val stable = MultiPersonPoseTracker(fps = 5, config = PoseTrackingConfig.standard(5))
        val weak = MultiPersonPoseTracker(fps = 5, config = PoseTrackingConfig.standard(5))
        repeat(3) { i ->
            val t = i * 200L
            stable.addFrame(t, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.9f)))
            weak.addFrame(t, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.2f)))
        }

        assertEquals(1, stable.buildTemplate()?.tracks?.size)
        assertNull(weak.buildTemplate())
    }

    @Test
    fun highRecallConfig_keepsWeakVisibilityTrack() {
        val tracker = MultiPersonPoseTracker(fps = 5, config = PoseTrackingConfig.highRecall(5))
        repeat(3) { i ->
            tracker.addFrame(i * 200L, 1080, 1920, listOf(pose(0.2f, 0.2f, 0.5f, 0.8f, visibility = 0.2f)))
        }

        assertEquals(1, tracker.buildTemplate()?.tracks?.size)
    }

    @Test
    fun trackingConfigPresets_haveExpectedValues() {
        val standard = PoseTrackingConfig.standard(5)
        assertEquals(3, standard.minTrackFrames)
        assertEquals(5, standard.maxMissedSteps)
        assertEquals(0.30f, standard.minAverageVisibility, 1e-6f)
        assertEquals(0.004f, standard.minAverageArea, 1e-6f)
        assertEquals(0.25f, standard.minTrackCoverage, 1e-6f)

        // The allowed gap never drops below two steps.
        assertEquals(2, PoseTrackingConfig.standard(1).maxMissedSteps)

        val highRecall = PoseTrackingConfig.highRecall(4)
        assertEquals(12, highRecall.maxMissedSteps)
        assertEquals(0.18f, highRecall.minAverageVisibility, 1e-6f)
        assertEquals(0.12f, highRecall.minTrackCoverage, 1e-6f)
        assertEquals(3, PoseTrackingConfig.highRecall(0).maxMissedSteps)
    }

    // ------------------------------------------------------------
    // PoseBounds and landmark helpers
    // ------------------------------------------------------------

    @Test
    fun poseBounds_derivedValues_areComputedFromEdges() {
        val bounds = PoseBounds(left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.9f)

        assertEquals(0.4f, bounds.width, 1e-6f)
        assertEquals(0.8f, bounds.height, 1e-6f)
        assertEquals(0.4f, bounds.centerX, 1e-6f)
        assertEquals(0.5f, bounds.centerY, 1e-6f)
        assertEquals(0.32f, bounds.area, 1e-6f)
        assertTrue(bounds.contains(0.4f, 0.5f))
        assertTrue(!bounds.contains(0.7f, 0.5f))
    }

    @Test
    fun poseBounds_invertedEdges_haveZeroSize() {
        val inverted = PoseBounds(left = 0.6f, top = 0.9f, right = 0.2f, bottom = 0.1f)

        assertEquals(0f, inverted.width, 0f)
        assertEquals(0f, inverted.height, 0f)
        assertEquals(0f, inverted.area, 0f)
    }

    @Test
    fun poseBoundsIou_identicalDisjointAndPartialOverlap() {
        val a = PoseBounds(0f, 0f, 1f, 1f)

        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(PoseBounds(2f, 2f, 3f, 3f)), 1e-6f)
        // Half of b overlaps a: intersection 0.5, union 1.5.
        assertEquals(1f / 3f, a.iou(PoseBounds(0.5f, 0f, 1.5f, 1f)), 1e-5f)
    }

    @Test
    fun poseBoundsIou_zeroAreaBoxes_returnsZero() {
        val empty = PoseBounds(0.5f, 0.5f, 0.5f, 0.5f)

        assertEquals(0f, empty.iou(empty), 0f)
    }

    @Test
    fun poseBounds_clampsPaddedBoxToUnitSquare() {
        val landmarks = listOf(0.02f, 0.98f, 0f, 1f)

        val bounds = landmarks.poseBounds()

        assertNotNull(bounds)
        bounds!!
        assertEquals(0f, bounds.left, 1e-6f)
        assertEquals(0.10f, bounds.right, 1e-6f)
        assertEquals(0.90f, bounds.top, 1e-6f)
        assertEquals(1f, bounds.bottom, 1e-6f)
    }

    @Test
    fun poseBounds_zeroPadding_givesTightBoxOverVisibleLandmarksOnly() {
        val landmarks = listOf(
            0.20f, 0.30f, 0f, 1.0f,
            0.60f, 0.70f, 0f, 1.0f,
            0.99f, 0.99f, 0f, 0.05f // hidden, ignored
        )

        val bounds = landmarks.poseBounds(padding = 0f)

        assertNotNull(bounds)
        bounds!!
        assertEquals(0.20f, bounds.left, 1e-6f)
        assertEquals(0.30f, bounds.top, 1e-6f)
        assertEquals(0.60f, bounds.right, 1e-6f)
        assertEquals(0.70f, bounds.bottom, 1e-6f)
    }

    @Test
    fun poseBounds_nullWhenNothingVisibleOrTooShort() {
        assertNull(listOf(0.5f, 0.5f, 0f, 0.1f).poseBounds())
        assertNull(listOf(0.5f, 0.5f, 0f).poseBounds())
        assertNull(emptyList<Float>().poseBounds())
    }

    @Test
    fun averageVisibility_averagesEveryFourthValue() {
        val landmarks = listOf(
            0.1f, 0.1f, 0f, 1.0f,
            0.2f, 0.2f, 0f, 0.5f,
            0.3f, 0.3f, 0f, 0.0f
        )

        assertEquals(0.5f, landmarks.averageVisibility(), 1e-6f)
        assertEquals(0f, listOf(1f, 1f, 1f).averageVisibility(), 0f)
        assertEquals(0f, emptyList<Float>().averageVisibility(), 0f)
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    /** Four-corner pose: enough landmarks to define a bounding box. */
    private fun pose(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        visibility: Float = 1f
    ): List<Float> = listOf(
        left, top, 0f, visibility,
        right, top, 0f, visibility,
        left, bottom, 0f, visibility,
        right, bottom, 0f, visibility
    )
}
