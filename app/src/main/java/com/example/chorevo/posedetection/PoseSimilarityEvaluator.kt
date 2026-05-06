package com.example.chorevo.posedetection

import com.example.chorevo.analytics.PerformanceSegment
import com.example.chorevo.analytics.SegmentLabel
import com.example.chorevo.analytics.TimelinePoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


class PoseSimilarityEvaluator(
    private val template: PoseTemplate,
    private val mirrorReferenceX: Boolean,
    private val minVisibility: Float = 0.3f,

    // scoring tunables
    private val sigma: Double = 0.85,           // larger => higher scores for same error
    private val ewmaBeta: Double = 0.08,         // responsiveness (not used in aggregates)
    private val scaleFloor: Float = 0.20f,       // avoid exploding error when shoulder/hip width is tiny

    // timeline/segments tunables
    private val timelineSampleEveryMs: Long = 250L, // 4 samples/sec
    private val goodThreshold: Double = 0.65,
    private val okThreshold: Double = 0.45
) {

    data class Snapshot(
        val frameCount: Int,
        val alignmentScore: Double,
        val timingConsistencyScore: Double,
        val smoothnessScore: Double,
        val avgPoseError: Double,

        // dashboard series (optional; only filled when includeSeries = true)
        val timelinePoints: List<TimelinePoint> = emptyList(),
        val performanceSegments: List<PerformanceSegment> = emptyList()
    )

    private val frames: List<PoseFrame> = template.frames.sortedBy { it.tMs }

    private var n = 0

    // score mean/variance (Welford) - KEEP
    private var meanScore = 0.0
    private var m2Score = 0.0

    // mean error - KEEP
    private var meanErr = 0.0

    // smoothness proxy via jerk on error - KEEP
    private var lastErr: Double? = null
    private var jerkSum = 0.0

    // EWMA score (reacts live) - KEEP (not persisted by default)
    private var ewmaScore: Double? = null

    // Stable joints only
    private val stableJoints = intArrayOf(
        11, 12, 13, 14, 15, 16,
        23, 24, 25, 26, 27, 28
    )

    // --- Dashboard series state ---
    private var nextTimelineSampleMs: Long = 0L
    private val timeline = mutableListOf<TimelinePoint>()

    private var currentSegmentStartMs: Long? = null
    private var currentSegmentLabel: SegmentLabel? = null
    private var segN = 0
    private var segScoreSum = 0.0
    private var segErrSum = 0.0
    private val segments = mutableListOf<PerformanceSegment>()

    /** Handles frame events. */
    fun onFrame(learnerFlat: List<Float>, timeMs: Long) {
        if (learnerFlat.size < 33 * 4) return
        if (frames.isEmpty()) return

        val ref = pickReferenceByTime(timeMs) ?: return
        if (ref.landmarks.size < 33 * 4) return

        val err = poseErrorNormalized(
            learner = learnerFlat,
            reference = ref.landmarks,
            mirrorRefX = mirrorReferenceX
        )

        // Gaussian mapping prevents collapse to ~0
        val score = gaussianScore(err, sigma).coerceIn(0.0, 1.0)

        // EWMA (optional)
        ewmaScore = if (ewmaScore == null) score else (ewmaScore!! + ewmaBeta * (score - ewmaScore!!))

        // Welford update for score (KEEP)
        n++
        val delta = score - meanScore
        meanScore += delta / n
        m2Score += delta * (score - meanScore)

        // mean error (KEEP)
        val derr = err - meanErr
        meanErr += derr / n

        // jerk (KEEP)
        lastErr?.let { prev -> jerkSum += abs(err - prev) }
        lastErr = err

        // ---- Dashboard: timeline (downsampled) ----
        if (n == 1) nextTimelineSampleMs = timeMs
        if (timeMs >= nextTimelineSampleMs) {
            timeline.add(TimelinePoint(tMs = timeMs, score = score, err = err))
            nextTimelineSampleMs = timeMs + timelineSampleEveryMs
        }

        // ---- Dashboard: segments ----
        val label = when {
            score >= goodThreshold -> SegmentLabel.GOOD
            score >= okThreshold -> SegmentLabel.OK
            else -> SegmentLabel.BAD
        }

        if (currentSegmentLabel == null) {
            currentSegmentLabel = label
            currentSegmentStartMs = timeMs
            segN = 0
            segScoreSum = 0.0
            segErrSum = 0.0
        }

        if (label != currentSegmentLabel) {
            // close previous segment
            closeSegment(endMs = timeMs)

            // start new
            currentSegmentLabel = label
            currentSegmentStartMs = timeMs
            segN = 0
            segScoreSum = 0.0
            segErrSum = 0.0
        }

        // accumulate current segment
        segN++
        segScoreSum += score
        segErrSum += err
    }

    /**
     * Snapshot of current session metrics.
     * - includeSeries=false for frequent UI updates (fast)
     * - includeSeries=true only when saving (returns timeline + segments)
     */
    fun snapshot(includeSeries: Boolean = false): Snapshot {
        val variance = if (n > 1) m2Score / (n - 1) else 0.0
        val std = sqrt(max(variance, 0.0))

        // Consistency from std of score (KEEP)
        val timingConsistency = (1.0 - (std / 0.25)).coerceIn(0.0, 1.0)

        // Smoothness from mean jerk (KEEP)
        val meanJerk = if (n > 2) jerkSum / (n - 1) else 0.0
        val smoothness = (1.0 - (meanJerk / 0.15)).coerceIn(0.0, 1.0)

        if (!includeSeries) {
            return Snapshot(
                frameCount = n,
                alignmentScore = meanScore.coerceIn(0.0, 1.0),
                timingConsistencyScore = timingConsistency,
                smoothnessScore = smoothness,
                avgPoseError = meanErr
            )
        }

        // finalize last open segment using latest timeline time
        val finalT = timeline.lastOrNull()?.tMs
        if (finalT != null) {
            finalizeSegments(finalT)
        }

        return Snapshot(
            frameCount = n,
            alignmentScore = meanScore.coerceIn(0.0, 1.0),
            timingConsistencyScore = timingConsistency,
            smoothnessScore = smoothness,
            avgPoseError = meanErr,
            timelinePoints = timeline.toList(),
            performanceSegments = segments.toList()
        )
    }

    /** Handles gaussian score. */
    private fun gaussianScore(err: Double, sigma: Double): Double {
        val s = max(1e-6, sigma)
        return exp(-(err * err) / (2.0 * s * s))
    }

    /** Selects reference by time. */
    private fun pickReferenceByTime(timeMs: Long): PoseFrame? {
        val fps = template.fps.coerceAtLeast(1)
        val idx = ((timeMs / 1000.0) * fps).toInt().coerceIn(0, frames.lastIndex)

        val cands = listOfNotNull(
            frames.getOrNull(idx - 1),
            frames.getOrNull(idx),
            frames.getOrNull(idx + 1)
        )
        return cands.minByOrNull { abs(it.tMs - timeMs) } ?: frames[idx]
    }

    /** Handles pose error normalized. */
    private fun poseErrorNormalized(
        learner: List<Float>,
        reference: List<Float>,
        mirrorRefX: Boolean
    ): Double {
        val ln = normalizeXY(learner, mirrorX = false)
        val rn = normalizeXY(reference, mirrorX = mirrorRefX)

        var sum = 0.0
        var wsum = 0.0

        for (idx in stableJoints) {
            val lBase = idx * 4
            val rBase = idx * 4

            val lv = learner[lBase + 3]
            val rv = reference[rBase + 3]
            val w = min(lv, rv).toDouble()

            if (w < minVisibility) continue

            val lx = ln[idx * 2]
            val ly = ln[idx * 2 + 1]
            val rx = rn[idx * 2]
            val ry = rn[idx * 2 + 1]

            val d = hypot((lx - rx).toDouble(), (ly - ry).toDouble())
            sum += w * d
            wsum += w
        }

        return if (wsum > 0.0) sum / wsum else 1.0
    }

    /** Handles normalize xy. */
    private fun normalizeXY(flat: List<Float>, mirrorX: Boolean): FloatArray {
        val out = FloatArray(33 * 2)

        /** Handles x. */
        fun x(i: Int): Float {
            val v = flat[i * 4]
            return if (mirrorX) 1f - v else v
        }
        /** Handles y. */
        fun y(i: Int): Float = flat[i * 4 + 1]
        /** Handles vis. */
        fun vis(i: Int): Float = flat[i * 4 + 3]

        val hipL = 23
        val hipR = 24
        val shL = 11
        val shR = 12

        val useHips = vis(hipL) > minVisibility && vis(hipR) > minVisibility
        val useSh = vis(shL) > minVisibility && vis(shR) > minVisibility

        val cx = when {
            useHips -> (x(hipL) + x(hipR)) / 2f
            useSh -> (x(shL) + x(shR)) / 2f
            else -> x(0)
        }
        val cy = when {
            useHips -> (y(hipL) + y(hipR)) / 2f
            useSh -> (y(shL) + y(shR)) / 2f
            else -> y(0)
        }

        val shoulderW = hypot(
            (x(shL) - x(shR)).toDouble(),
            (y(shL) - y(shR)).toDouble()
        ).toFloat()

        val hipW = hypot(
            (x(hipL) - x(hipR)).toDouble(),
            (y(hipL) - y(hipR)).toDouble()
        ).toFloat()

        val rawScale = when {
            useSh -> shoulderW
            useHips -> hipW
            else -> 1f
        }

        val scale = max(rawScale, scaleFloor)

        for (i in 0 until 33) {
            out[i * 2] = (x(i) - cx) / scale
            out[i * 2 + 1] = (y(i) - cy) / scale
        }

        return out
    }

    /** Closes segment. */
    private fun closeSegment(endMs: Long) {
        val start = currentSegmentStartMs ?: return
        val label = currentSegmentLabel ?: return
        if (endMs <= start) return

        val avgS = if (segN > 0) segScoreSum / segN else meanScore
        val avgE = if (segN > 0) segErrSum / segN else meanErr

        segments.add(
            PerformanceSegment(
                startMs = start,
                endMs = endMs,
                label = label,
                avgScore = avgS,
                avgErr = avgE
            )
        )
    }

    /** Handles finalize segments. */
    private fun finalizeSegments(finalTimeMs: Long) {
        // if we already finalized (cleared), skip
        if (currentSegmentLabel == null || currentSegmentStartMs == null) return

        closeSegment(endMs = finalTimeMs)

        // prevent double-finalize
        currentSegmentStartMs = null
        currentSegmentLabel = null
    }
}
