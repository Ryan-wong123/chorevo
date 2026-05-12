package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun fromJson_readsLegacyTemplateWithOnlyFpsAndFrames() {
        val json = """
            {
              "fps": 2,
              "frames": [
                {
                  "tms": 0,
                  "landmarks": [0.125, 0.25, -0.5, 0.875, 0.5, 0.625, 0.0, 0.75]
                }
              ]
            }
        """.trimIndent()

        val restored = PoseTemplateJson.fromJson(json)

        assertEquals(2, restored.fps)
        assertEquals(1, restored.frames.size)
        assertEquals(emptyList<PoseTrack>(), restored.tracks)
        assertNull(restored.defaultTrackId)
        assertNull(restored.selectionPreview)
        assertEquals(emptyList<PoseAnalysisWarning>(), restored.qualityWarnings)
        assertEquals(1, restored.availableTracks().size)
        assertEquals(PoseTemplate.LEGACY_TRACK_ID, restored.availableTracks().first().trackId)
        assertFloatListEquals(
            listOf(0.125f, 0.25f, -0.5f, 0.875f, 0.5f, 0.625f, 0f, 0.75f),
            restored.frames.first().landmarks
        )
    }

    @Test
    fun fromJson_defaultsMissingOptionalQualityMetadata() {
        val json = """
            {
              "fps": 8,
              "frames": [],
              "tracks": [
                {
                  "trackId": "track_1",
                  "label": "Dancer 1",
                  "frames": [
                    {"tms": 125, "landmarks": [0.1, 0.2, 0.3, 0.4]}
                  ],
                  "avgVisibility": 0.4
                }
              ],
              "defaultTrackId": "track_1",
              "selectionPreview": null
            }
        """.trimIndent()

        val restored = PoseTemplateJson.fromJson(json)

        assertEquals("track_1", restored.defaultTrackId)
        assertEquals(1, restored.tracks.size)
        assertEquals(emptyList<PoseAnalysisWarning>(), restored.qualityWarnings)
    }

    @Test
    fun fromJson_ignoresUnknownFieldsAndMalformedOptionalMetadata() {
        val json = """
            {
              "fps": 8,
              "frames": [{"tms": 0, "landmarks": [0.1, 0.2, 0.3, 0.4]}],
              "tracks": "not-a-track-array",
              "defaultTrackId": 42,
              "selectionPreview": "not-a-preview-object",
              "qualityWarnings": [
                {"code": "lowLight", "message": "Low light"},
                {"message": "Missing code"},
                "bad-warning"
              ],
              "futureMetadata": {"detector": "optional"},
              "futureArray": [1, 2, 3]
            }
        """.trimIndent()

        val restored = PoseTemplateJson.fromJson(json)

        assertEquals(8, restored.fps)
        assertEquals(1, restored.frames.size)
        assertTrue(restored.tracks.isEmpty())
        assertNull(restored.defaultTrackId)
        assertNull(restored.selectionPreview)
        assertEquals(listOf(PoseAnalysisWarning("lowLight", "Low light")), restored.qualityWarnings)
    }

    @Test
    fun roundTrip_preservesMultiTrackTemplateAndFlattenedLandmarks() {
        val trackOneFrame = PoseFrame(
            tMs = 33L,
            landmarks = listOf(0f, 0.125f, -0.25f, 1f, 0.33333334f, 0.6666667f, 0.5f, 0.25f)
        )
        val trackTwoFrame = PoseFrame(
            tMs = 33L,
            landmarks = listOf(1f, 0.875f, 0.25f, 0.5f, 0.44444445f, 0.5555556f, -0.125f, 0.125f)
        )
        val template = PoseTemplate(
            fps = 8,
            frames = listOf(trackOneFrame),
            tracks = listOf(
                PoseTrack(
                    trackId = "track_1",
                    label = "Lead",
                    frames = listOf(trackOneFrame),
                    avgVisibility = 0.625f
                ),
                PoseTrack(
                    trackId = "track_2",
                    label = "Support",
                    frames = listOf(trackTwoFrame),
                    avgVisibility = 0.3125f
                )
            ),
            defaultTrackId = "track_2",
            selectionPreview = PoseSelectionPreview(
                timeMs = 33L,
                frameWidth = 720,
                frameHeight = 1280,
                candidates = listOf(
                    PoseSelectionCandidate(
                        trackId = "track_1",
                        landmarks = trackOneFrame.landmarks,
                        bounds = PoseBounds(0.1f, 0.2f, 0.4f, 0.8f)
                    ),
                    PoseSelectionCandidate(
                        trackId = "track_2",
                        landmarks = trackTwoFrame.landmarks,
                        bounds = PoseBounds(0.5f, 0.2f, 0.9f, 0.8f)
                    )
                )
            ),
            qualityWarnings = listOf(PoseAnalysisWarning("interpolatedLandmarks", "Short gaps were inferred."))
        )

        val restored = PoseTemplateJson.fromJson(PoseTemplateJson.toJson(template))

        assertEquals(template.fps, restored.fps)
        assertEquals(template.defaultTrackId, restored.defaultTrackId)
        assertEquals(template.selectionPreview, restored.selectionPreview)
        assertEquals(template.qualityWarnings, restored.qualityWarnings)
        assertEquals(template.tracks.map { it.trackId }, restored.tracks.map { it.trackId })
        assertFloatListEquals(trackOneFrame.landmarks, restored.tracks[0].frames[0].landmarks)
        assertFloatListEquals(trackTwoFrame.landmarks, restored.tracks[1].frames[0].landmarks)
    }

    @Test
    fun jsonPath_preservesDanceTemplateStorageLocation() {
        assertEquals("danceTemplates/dance123.json", PoseTemplateStorage.jsonPath("dance123"))
    }

    private fun assertFloatListEquals(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedValue, actualValue) ->
            assertEquals(expectedValue, actualValue, 0.000001f)
        }
    }
}
