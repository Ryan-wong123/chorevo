package com.example.chorevo.posedetection

import kotlin.math.PI
import kotlin.math.abs

data class OneEuroFilterConfig(
    /** Lower = smoother at rest (more jitter removed). */
    val minCutoffHz: Float = 1.0f,
    /** Higher = less lag during fast movement. */
    val beta: Float = 2.0f,
    val derivativeCutoffHz: Float = 1.0f
)

/**
 * One-Euro filter over flat landmark arrays (`x, y, z, visibility` per landmark, the layout
 * used by [HolisticFrameLandmarks.pose]). Smooths x/y/z only; visibility passes through.
 *
 * Not thread-safe; use from a single analysis thread and call [reset] when tracking restarts.
 */
class OneEuroLandmarkFilter(
    private val config: OneEuroFilterConfig = OneEuroFilterConfig(),
    private val stride: Int = 4,
    private val axes: Int = 3
) {
    private var previousRaw: FloatArray? = null
    private var previousFiltered: FloatArray? = null
    private var previousDerivative: FloatArray? = null
    private var previousTimestampMs: Long = 0L

    fun reset() {
        previousRaw = null
        previousFiltered = null
        previousDerivative = null
    }

    fun filter(landmarks: List<Float>, timestampMs: Long): List<Float> {
        if (landmarks.isEmpty()) {
            reset()
            return landmarks
        }
        val raw = landmarks.toFloatArray()
        val lastRaw = previousRaw
        val lastFiltered = previousFiltered
        val dtMs = timestampMs - previousTimestampMs
        if (lastRaw == null || lastFiltered == null || lastRaw.size != raw.size || dtMs <= 0L) {
            previousRaw = raw
            previousFiltered = raw.copyOf()
            previousDerivative = FloatArray(raw.size)
            previousTimestampMs = timestampMs
            return landmarks
        }

        val dt = dtMs / 1000f
        val derivatives = previousDerivative ?: FloatArray(raw.size)
        val filtered = raw.copyOf()
        val derivativeAlpha = alpha(config.derivativeCutoffHz, dt)
        for (base in raw.indices step stride) {
            for (axis in 0 until axes) {
                val i = base + axis
                if (i >= raw.size) break
                val rawDerivative = (raw[i] - lastRaw[i]) / dt
                derivatives[i] = derivativeAlpha * rawDerivative + (1f - derivativeAlpha) * derivatives[i]
                val cutoff = config.minCutoffHz + config.beta * abs(derivatives[i])
                val a = alpha(cutoff, dt)
                filtered[i] = a * raw[i] + (1f - a) * lastFiltered[i]
            }
        }
        previousRaw = raw
        previousFiltered = filtered
        previousDerivative = derivatives
        previousTimestampMs = timestampMs
        return filtered.asList()
    }

    private fun alpha(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dtSeconds)
    }
}
