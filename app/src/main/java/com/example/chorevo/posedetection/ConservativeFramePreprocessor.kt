package com.example.chorevo.posedetection

import android.graphics.Bitmap
import kotlin.math.pow
import kotlin.math.roundToInt

class ConservativeFramePreprocessor(
    private val config: FramePreprocessorConfig = FramePreprocessorConfig()
) : FramePreprocessor {

    override fun process(frame: Bitmap, timestampMs: Long): FramePreprocessorResult {
        if (!config.enabled) {
            return NoOpFramePreprocessor.process(frame, timestampMs)
        }

        val sourcePixels = IntArray(frame.width * frame.height)
        frame.getPixels(sourcePixels, 0, frame.width, 0, 0, frame.width, frame.height)

        val averageLuminance = averageLuminance(sourcePixels)
        val processedPixels = IntArray(sourcePixels.size)
        for (i in sourcePixels.indices) {
            processedPixels[i] = transformColor(
                color = sourcePixels[i],
                brightness = config.brightness,
                contrast = config.contrast,
                gamma = config.gamma
            )
        }

        val processedBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        processedBitmap.setPixels(processedPixels, 0, frame.width, 0, 0, frame.width, frame.height)

        return FramePreprocessorResult(
            bitmap = processedBitmap,
            ownsBitmap = true,
            warnings = lowLightWarnings(averageLuminance)
        )
    }

    private fun lowLightWarnings(averageLuminance: Float): List<PoseAnalysisWarning> {
        if (averageLuminance >= config.lowLightWarningThreshold) return emptyList()
        return listOf(
            PoseAnalysisWarning(
                code = LOW_LIGHT_WARNING_CODE,
                message = "Low-light video quality may reduce dancer detection and landmark accuracy."
            )
        )
    }

    companion object {
        const val LOW_LIGHT_WARNING_CODE = "lowLightQuality"

        internal fun transformColor(
            color: Int,
            brightness: Float,
            contrast: Float,
            gamma: Float
        ): Int {
            val alpha = color ushr 24
            val safeContrast = contrast.coerceIn(0.5f, 1.5f)
            val safeGamma = gamma.coerceIn(0.5f, 2.2f)

            val red = transformChannel(color shr 16 and 0xff, brightness, safeContrast, safeGamma)
            val green = transformChannel(color shr 8 and 0xff, brightness, safeContrast, safeGamma)
            val blue = transformChannel(color and 0xff, brightness, safeContrast, safeGamma)

            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        internal fun averageLuminance(colors: IntArray): Float {
            if (colors.isEmpty()) return 255f
            var sum = 0.0
            for (color in colors) {
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                sum += 0.2126 * red + 0.7152 * green + 0.0722 * blue
            }
            return (sum / colors.size.toDouble()).toFloat()
        }

        private fun transformChannel(
            value: Int,
            brightness: Float,
            contrast: Float,
            gamma: Float
        ): Int {
            val contrastAdjusted = ((value - 127.5f) * contrast) + 127.5f + brightness
            val normalized = contrastAdjusted.coerceIn(0f, 255f) / 255f
            return (normalized.pow(1f / gamma) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
        }
    }
}
