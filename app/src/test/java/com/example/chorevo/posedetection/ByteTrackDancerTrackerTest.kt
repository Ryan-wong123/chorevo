package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackDancerTrackerTest {

    @Test
    fun update_recoversSameTrackAfterShortDetectionGap() {
        val tracker = ByteTrackDancerTracker(
            config = DancerTrackerConfig(
                iouMatchThreshold = 0.20f,
                highConfidenceThreshold = 0.50f,
                lowConfidenceThreshold = 0.10f,
                maxMissedFrames = 3
            )
        )

        val first = tracker.update(
            frameIndex = 0,
            timestampMs = 0L,
            detections = listOf(box(0.10f, 0.20f, 0.30f, 0.70f, 0.90f))
        )
        val lost = tracker.update(
            frameIndex = 1,
            timestampMs = 125L,
            detections = emptyList()
        )
        val recovered = tracker.update(
            frameIndex = 2,
            timestampMs = 250L,
            detections = listOf(box(0.11f, 0.21f, 0.31f, 0.71f, 0.86f))
        )

        assertEquals("track_1", first.single().trackId)
        assertEquals(TrackState.TemporarilyLost, lost.single().state)
        assertFalse(lost.any { it.state == TrackState.Visible })
        assertEquals("track_1", recovered.single().trackId)
        assertEquals(TrackState.Recovered, recovered.single().state)
        assertEquals(1, tracker.tracks().size)
    }

    @Test
    fun update_endsTrackAfterMaxMissedFrames() {
        val tracker = ByteTrackDancerTracker(
            config = DancerTrackerConfig(maxMissedFrames = 1)
        )

        tracker.update(
            frameIndex = 0,
            timestampMs = 0L,
            detections = listOf(box(0.20f, 0.20f, 0.40f, 0.75f, 0.90f))
        )
        tracker.update(frameIndex = 1, timestampMs = 125L, detections = emptyList())
        val ended = tracker.update(frameIndex = 2, timestampMs = 250L, detections = emptyList())

        assertEquals(TrackState.Ended, ended.single().state)
    }

    @Test
    fun update_deduplicatesOverlappingBoxesForOneDancer() {
        val tracker = ByteTrackDancerTracker(
            config = DancerTrackerConfig(highConfidenceThreshold = 0.50f)
        )

        val trackedBoxes = tracker.update(
            frameIndex = 0,
            timestampMs = 0L,
            detections = listOf(
                box(0.10f, 0.20f, 0.30f, 0.72f, 0.90f),
                box(0.11f, 0.21f, 0.31f, 0.73f, 0.80f)
            )
        )

        assertEquals(1, trackedBoxes.count { it.state == TrackState.Visible })
        assertEquals(1, tracker.tracks().size)
        assertEquals("track_1", trackedBoxes.single().trackId)
    }

    @Test
    fun update_emptyDetectionsDoNotCreateVisibleBoxes() {
        val tracker = ByteTrackDancerTracker()

        val trackedBoxes = tracker.update(
            frameIndex = 0,
            timestampMs = 0L,
            detections = emptyList()
        )

        assertTrue(trackedBoxes.none { it.state == TrackState.Visible })
        assertEquals(emptyList<DancerTrack>(), tracker.tracks())
    }

    private fun box(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float
    ): DetectionBox {
        return DetectionBox.normalized(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = confidence,
            source = DetectionSource.Yolo
        )
    }
}
