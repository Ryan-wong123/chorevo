package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PoseTemplateJsonTest {

    @Test
    fun roundTrip_preservesTemplateData() {
        val template = PoseTemplate(
            fps = 15,
            frames = listOf(
                PoseFrame(tMs = 0L, landmarks = listOf(0.1f, 0.2f, 0.3f, 0.9f)),
                PoseFrame(tMs = 66L, landmarks = listOf(0.4f, 0.5f, 0.6f, 0.8f))
            ),
            tracks = listOf(
                PoseTrack(
                    trackId = "track_1",
                    label = "Dancer 1",
                    frames = listOf(PoseFrame(tMs = 0L, landmarks = listOf(0.1f, 0.2f, 0.3f, 0.9f))),
                    avgVisibility = 0.85f
                )
            ),
            defaultTrackId = "track_1",
            selectionPreview = PoseSelectionPreview(
                timeMs = 0L,
                frameWidth = 1080,
                frameHeight = 1920,
                candidates = listOf(
                    PoseSelectionCandidate(
                        trackId = "track_1",
                        landmarks = listOf(0.1f, 0.2f, 0.3f, 0.9f),
                        bounds = PoseBounds(left = 0.1f, top = 0.2f, right = 0.7f, bottom = 0.9f)
                    )
                )
            ),
            qualityWarnings = listOf(
                PoseAnalysisWarning(
                    code = "manyMissingFrames",
                    message = "Some frames were missing."
                )
            )
        )

        val restored = PoseTemplateJson.fromJson(PoseTemplateJson.toJson(template))

        assertEquals(template.fps, restored.fps)
        assertEquals(template.frames, restored.frames)
        assertEquals(template.tracks, restored.tracks)
        assertEquals(template.defaultTrackId, restored.defaultTrackId)
        assertNotNull(restored.selectionPreview)
        assertEquals(template.selectionPreview, restored.selectionPreview)
        assertEquals(template.qualityWarnings, restored.qualityWarnings)
    }
}
