package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extra pure-helper tests for [VideoPoseTemplateGenerator]. Basic samplingFps/frameTimeMs
 * behaviour is in SamplingFpsTest; the analysis pipeline itself needs MediaPipe, MediaCodec
 * and Bitmaps, so only the companion helpers and constants are covered here.
 */
class VideoPoseTemplateGeneratorTest {

    @Test
    fun samplingFps_negativeDuration_usesPreferredRate() {
        assertEquals(8, VideoPoseTemplateGenerator.samplingFps(-5_000L, 8))
    }

    @Test
    fun samplingFps_exactlyAtFrameBudget_keepsPreferredRate() {
        // 960 frames / 120 s = 8 fps exactly.
        assertEquals(8, VideoPoseTemplateGenerator.samplingFps(120_000L, 8))
        // A millisecond longer drops to 7 fps (integer division of the budget).
        assertEquals(7, VideoPoseTemplateGenerator.samplingFps(120_001L, 8))
    }

    @Test
    fun samplingFps_preferredBelowMinimum_isNotRaised() {
        assertEquals(2, VideoPoseTemplateGenerator.samplingFps(60_000L, 2))
        assertEquals(1, VideoPoseTemplateGenerator.samplingFps(10_000L, 1))
    }

    @Test
    fun samplingFps_veryLongVideo_isClampedToMinimumOfThree() {
        assertEquals(3, VideoPoseTemplateGenerator.samplingFps(3_600_000L, 8))
        assertEquals(3, VideoPoseTemplateGenerator.samplingFps(Long.MAX_VALUE / 2, 5))
    }

    @Test
    fun samplingFps_neverExceedsFrameBudget_forAnalysedDurations() {
        for (seconds in listOf(130L, 150L, 200L, 250L, 300L)) {
            val fps = VideoPoseTemplateGenerator.samplingFps(seconds * 1000L, 8)
            assertTrue("seconds=$seconds fps=$fps", seconds * fps <= 960L)
        }
    }

    @Test
    fun samplingFps_neverExceedsPreferred_whenPreferredAboveMinimum() {
        for (preferred in 3..8) {
            for (seconds in listOf(10L, 90L, 200L, 600L)) {
                val fps = VideoPoseTemplateGenerator.samplingFps(seconds * 1000L, preferred)
                assertTrue("preferred=$preferred seconds=$seconds fps=$fps", fps <= preferred)
                assertTrue("preferred=$preferred seconds=$seconds fps=$fps", fps >= 3)
            }
        }
    }

    @Test
    fun frameTimeMs_firstFrameIsAlwaysZero() {
        for (fps in 1..10) {
            assertEquals(0L, VideoPoseTemplateGenerator.frameTimeMs(0, fps))
        }
    }

    @Test
    fun frameTimeMs_nonPositiveFps_isTreatedAsOneFps() {
        assertEquals(3_000L, VideoPoseTemplateGenerator.frameTimeMs(3, 0))
        assertEquals(3_000L, VideoPoseTemplateGenerator.frameTimeMs(3, -4))
    }

    @Test
    fun frameTimeMs_isMonotonicallyIncreasing() {
        var previous = -1L
        for (index in 0..200) {
            val t = VideoPoseTemplateGenerator.frameTimeMs(index, 7)
            assertTrue("index=$index", t > previous)
            previous = t
        }
    }

    @Test
    fun constants_exposeExpectedValues() {
        assertEquals(5 * 60_000L, VideoPoseTemplateGenerator.MAX_ANALYSED_DURATION_MS)
        assertEquals("handExtractionPartial", VideoPoseTemplateGenerator.WARNING_HAND_EXTRACTION_PARTIAL)
        assertEquals("videoTrimmed", VideoPoseTemplateGenerator.WARNING_VIDEO_TRIMMED)
    }

    @Test
    fun generationProfiles_standardAndHighRecallExist() {
        assertEquals(
            listOf("STANDARD", "HIGH_RECALL"),
            VideoPoseTemplateGenerator.GenerationProfile.values().map { it.name }
        )
    }
}
