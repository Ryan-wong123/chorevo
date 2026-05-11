package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConservativeFramePreprocessorTest {

    @Test
    fun disabledConfig_keepsAdjustmentsConfigurable() {
        val config = FramePreprocessorConfig.disabled()

        assertEquals(false, config.enabled)
        assertEquals(FramePreprocessorConfig.DEFAULT_BRIGHTNESS, config.brightness, 0.001f)
        assertEquals(FramePreprocessorConfig.DEFAULT_CONTRAST, config.contrast, 0.001f)
        assertEquals(FramePreprocessorConfig.DEFAULT_GAMMA, config.gamma, 0.001f)
    }

    @Test
    fun transformColor_appliesConservativeBrightnessWithoutChangingAlpha() {
        val darkGray = argb(alpha = 128, red = 32, green = 32, blue = 32)

        val transformed = ConservativeFramePreprocessor.transformColor(
            color = darkGray,
            brightness = FramePreprocessorConfig.DEFAULT_BRIGHTNESS,
            contrast = FramePreprocessorConfig.DEFAULT_CONTRAST,
            gamma = FramePreprocessorConfig.DEFAULT_GAMMA
        )

        assertEquals(128, transformed ushr 24)
        assertTrue((transformed shr 16 and 0xff) > 32)
        assertTrue((transformed shr 8 and 0xff) > 32)
        assertTrue((transformed and 0xff) > 32)
    }

    @Test
    fun transformColor_clampsVeryBrightPixels() {
        val bright = argb(alpha = 255, red = 252, green = 253, blue = 254)

        val transformed = ConservativeFramePreprocessor.transformColor(
            color = bright,
            brightness = 40f,
            contrast = 1.5f,
            gamma = 1.4f
        )

        assertEquals(argb(alpha = 255, red = 255, green = 255, blue = 255), transformed)
    }

    @Test
    fun averageLuminance_identifiesDarkFramesBelowDefaultWarningThreshold() {
        val pixels = intArrayOf(
            argb(alpha = 255, red = 20, green = 20, blue = 20),
            argb(alpha = 255, red = 30, green = 30, blue = 30)
        )

        val luminance = ConservativeFramePreprocessor.averageLuminance(pixels)

        assertTrue(luminance < FramePreprocessorConfig.DEFAULT_LOW_LIGHT_WARNING_THRESHOLD)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
