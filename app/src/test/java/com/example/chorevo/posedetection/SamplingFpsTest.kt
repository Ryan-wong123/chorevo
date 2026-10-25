package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SamplingFpsTest {

    private fun fps(seconds: Long, preferred: Int) =
        VideoPoseTemplateGenerator.samplingFps(seconds * 1000L, preferred)

    @Test
    fun shortVideos_keepThePreferredRate() {
        assertEquals(8, fps(30, 8))
        assertEquals(8, fps(120, 8))
        assertEquals(5, fps(120, 5))
    }

    @Test
    fun longerVideos_areSampledLessOften_withinTheFrameBudget() {
        assertEquals(5, fps(180, 8))   // 960 frames / 180 s
        assertEquals(4, fps(240, 8))
        assertEquals(3, fps(300, 8))   // 5 minutes
    }

    @Test
    fun neverBelowThreeFramesPerSecond() {
        assertEquals(3, fps(600, 8))
    }

    @Test
    fun unknownDuration_usesThePreferredRate() {
        assertEquals(8, VideoPoseTemplateGenerator.samplingFps(0L, 8))
    }

    @Test
    fun frameTimes_dontDrift_atAnyRate() {
        for (fps in 3..8) {
            for (index in listOf(0, 1, 7, 100, 959)) {
                val t = VideoPoseTemplateGenerator.frameTimeMs(index, fps)
                // Within a millisecond of the exact time, at any index.
                assertTrue("fps=$fps index=$index", abs(t - index * 1000.0 / fps) < 1.0)
            }
        }
        // Adding a rounded-down 142 ms step would put frame 960 at 136_320 ms, 0.8 s early.
        assertEquals(137_142L, VideoPoseTemplateGenerator.frameTimeMs(960, 7))
    }
}
