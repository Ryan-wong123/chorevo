package com.example.chorevo.posedetection

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroLandmarkFilterTest {

    private fun landmark(x: Float) = listOf(x, 0.5f, 0f, 0.9f)

    @Test
    fun firstFramePassesThrough() {
        val filter = OneEuroLandmarkFilter()
        assertEquals(landmark(0.3f), filter.filter(landmark(0.3f), 0L))
    }

    @Test
    fun jitterAtRestIsReduced() {
        val filter = OneEuroLandmarkFilter()
        filter.filter(landmark(0.5f), 0L)
        var maxDeviation = 0f
        var raw = 0.5f
        for (i in 1..30) {
            raw = if (i % 2 == 0) 0.51f else 0.49f
            val out = filter.filter(landmark(raw), i * 33L)
            maxDeviation = maxOf(maxDeviation, abs(out[0] - 0.5f))
        }
        assertTrue("deviation $maxDeviation", maxDeviation < 0.008f)
    }

    @Test
    fun fastMovementStillTracksClosely() {
        val filter = OneEuroLandmarkFilter()
        filter.filter(landmark(0.1f), 0L)
        var out = listOf(0f)
        for (i in 1..10) {
            out = filter.filter(landmark(0.1f + 0.08f * i), i * 33L)
        }
        assertTrue("lag ${0.9f - out[0]}", 0.9f - out[0] < 0.2f)
    }

    @Test
    fun visibilityIsNotSmoothed() {
        val filter = OneEuroLandmarkFilter()
        filter.filter(listOf(0.5f, 0.5f, 0f, 0.9f), 0L)
        val out = filter.filter(listOf(0.5f, 0.5f, 0f, 0.1f), 33L)
        assertEquals(0.1f, out[3], 0f)
    }

    @Test
    fun emptyInputResetsState() {
        val filter = OneEuroLandmarkFilter()
        filter.filter(landmark(0.1f), 0L)
        filter.filter(emptyList(), 33L)
        assertEquals(landmark(0.9f), filter.filter(landmark(0.9f), 66L))
    }
}
