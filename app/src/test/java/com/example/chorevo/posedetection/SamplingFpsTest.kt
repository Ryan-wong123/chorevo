package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
