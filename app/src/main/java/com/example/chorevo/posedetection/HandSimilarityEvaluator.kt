package com.example.chorevo.posedetection

import com.example.chorevo.analytics.FeedbackMessage
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HandSimilarityEvaluator(
    template: PoseTemplate,
    referenceTrackId: String? = null,
    private val minVisibility: Float = 0.25f,
    private val sigma: Double = 0.55,
    private val scaleFloor: Float = 0.04f
) {
    data class Snapshot(
        val frameCount: Int,
        val handScore: Double?,
        val leftHandScore: Double?,
        val rightHandScore: Double?,
        val hasReferenceHands: Boolean,
        val latestFeedback: String? = null,
        val feedbackMessages: List<FeedbackMessage> = emptyList()
    )

    private val frames = template.resolveHandFrames(referenceTrackId)
    private val hasLeftReference = frames.any { it.leftHand.isValidHand() }
    private val hasRightReference = frames.any { it.rightHand.isValidHand() }
    private val hasReferenceHands = hasLeftReference || hasRightReference
    private val feedbackMessages = mutableListOf<FeedbackMessage>()
    private val seenFeedbackMessages = mutableSetOf<String>()

    private var validFrameCount = 0
    private var latestFeedback: String? = null
    private var leftCount = 0
    private var rightCount = 0
    private var leftMean = 0.0
    private var rightMean = 0.0

    fun onFrame(
        learnerLeftHand: List<Float>,
        learnerRightHand: List<Float>,
        timeMs: Long
    ) {
        if (!hasReferenceHands || frames.isEmpty()) return

        val ref = pickReferenceByTime(timeMs) ?: return
        val leftRefExists = ref.leftHand.isValidHand()
        val rightRefExists = ref.rightHand.isValidHand()
        val leftLearnerExists = learnerLeftHand.isValidHand()
        val rightLearnerExists = learnerRightHand.isValidHand()

        val leftScore = if (leftRefExists && leftLearnerExists) {
            scoreHand(learnerLeftHand, ref.leftHand)
        } else {
            null
        }
        val rightScore = if (rightRefExists && rightLearnerExists) {
            scoreHand(learnerRightHand, ref.rightHand)
        } else {
            null
        }

        if (leftScore != null) {
            leftCount++
            leftMean += (leftScore - leftMean) / leftCount
        }
        if (rightScore != null) {
            rightCount++
            rightMean += (rightScore - rightMean) / rightCount
        }
        if (leftScore != null || rightScore != null) {
            validFrameCount++
        }

        val feedback = buildFeedback(
            leftRefExists = leftRefExists,
            rightRefExists = rightRefExists,
            leftLearnerExists = leftLearnerExists,
            rightLearnerExists = rightLearnerExists,
            leftScore = leftScore,
            rightScore = rightScore
        )
        latestFeedback = feedback
        if (feedback != null && seenFeedbackMessages.add(feedback)) {
            feedbackMessages.add(FeedbackMessage(tMs = timeMs, message = feedback))
        }
    }

    fun snapshot(): Snapshot {
        val handScores = listOfNotNull(
            leftMean.takeIf { leftCount > 0 },
            rightMean.takeIf { rightCount > 0 }
        )
        return Snapshot(
            frameCount = validFrameCount,
            handScore = handScores.takeIf { it.isNotEmpty() }?.average()?.coerceIn(0.0, 1.0),
            leftHandScore = leftMean.takeIf { leftCount > 0 }?.coerceIn(0.0, 1.0),
            rightHandScore = rightMean.takeIf { rightCount > 0 }?.coerceIn(0.0, 1.0),
            hasReferenceHands = hasReferenceHands,
            latestFeedback = latestFeedback,
            feedbackMessages = feedbackMessages.toList()
        )
    }

    private fun pickReferenceByTime(timeMs: Long): HandLandmarkFrame? {
        return frames.minByOrNull { kotlin.math.abs(it.tMs - timeMs) }
    }

    private fun scoreHand(learner: List<Float>, reference: List<Float>): Double? {
        val learnerNormalized = normalizeHand(learner) ?: return null
        val referenceNormalized = normalizeHand(reference) ?: return null

        var sum = 0.0
        var weightSum = 0.0
        for (idx in 1 until HAND_LANDMARK_COUNT) {
            val base = idx * LANDMARK_VALUE_COUNT
            val visibility = min(learner[base + 3], reference[base + 3]).toDouble()
            if (visibility < minVisibility) continue

            val lx = learnerNormalized[idx * 2]
            val ly = learnerNormalized[idx * 2 + 1]
            val rx = referenceNormalized[idx * 2]
            val ry = referenceNormalized[idx * 2 + 1]
            sum += visibility * hypot((lx - rx).toDouble(), (ly - ry).toDouble())
            weightSum += visibility
        }

        if (weightSum <= 0.0) return null
        return exp(-((sum / weightSum) * (sum / weightSum)) / (2.0 * sigma * sigma))
            .coerceIn(0.0, 1.0)
    }

    private fun normalizeHand(flat: List<Float>): FloatArray? {
        if (!flat.isValidHand()) return null

        val wristX = flat[0]
        val wristY = flat[1]
        val scale = max(palmScale(flat), scaleFloor)
        val out = FloatArray(HAND_LANDMARK_COUNT * 2)
        for (idx in 0 until HAND_LANDMARK_COUNT) {
            val base = idx * LANDMARK_VALUE_COUNT
            out[idx * 2] = (flat[base] - wristX) / scale
            out[idx * 2 + 1] = (flat[base + 1] - wristY) / scale
        }
        return out
    }

    private fun palmScale(flat: List<Float>): Float {
        val distances = mutableListOf<Float>()
        for (idx in MCP_INDICES) {
            val base = idx * LANDMARK_VALUE_COUNT
            val visibility = min(flat[3], flat[base + 3])
            if (visibility >= minVisibility) {
                distances.add(
                    hypot(
                        (flat[base] - flat[0]).toDouble(),
                        (flat[base + 1] - flat[1]).toDouble()
                    ).toFloat()
                )
            }
        }

        if (distances.size >= 2) return distances.average().toFloat()

        val visibleXs = mutableListOf<Float>()
        val visibleYs = mutableListOf<Float>()
        for (idx in 0 until HAND_LANDMARK_COUNT) {
            val base = idx * LANDMARK_VALUE_COUNT
            if (flat[base + 3] >= minVisibility) {
                visibleXs.add(flat[base])
                visibleYs.add(flat[base + 1])
            }
        }
        if (visibleXs.size < 2) return scaleFloor

        val width = visibleXs.maxOrNull()!! - visibleXs.minOrNull()!!
        val height = visibleYs.maxOrNull()!! - visibleYs.minOrNull()!!
        return sqrt(width * width + height * height)
    }

    private fun buildFeedback(
        leftRefExists: Boolean,
        rightRefExists: Boolean,
        leftLearnerExists: Boolean,
        rightLearnerExists: Boolean,
        leftScore: Double?,
        rightScore: Double?
    ): String? {
        val missingLeft = leftRefExists && !leftLearnerExists
        val missingRight = rightRefExists && !rightLearnerExists
        return when {
            missingLeft && missingRight -> "Keep hands visible."
            missingLeft -> "Left hand not detected."
            missingRight -> "Right hand not detected."
            listOfNotNull(leftScore, rightScore).any { it < MATCH_FEEDBACK_THRESHOLD } -> "Match the hand shape."
            listOfNotNull(leftScore, rightScore).isNotEmpty() -> "Good hand shape."
            else -> null
        }
    }

    private fun PoseTemplate.resolveHandFrames(referenceTrackId: String?): List<HandLandmarkFrame> {
        if (handTracks.isEmpty()) return emptyList()
        return handTracks.firstOrNull { it.trackId == referenceTrackId }?.frames.orEmpty()
            .ifEmpty { handTracks.firstOrNull { it.trackId == defaultTrackId }?.frames.orEmpty() }
            .ifEmpty { handTracks.firstOrNull { it.trackId == PoseTemplate.LEGACY_TRACK_ID }?.frames.orEmpty() }
            .ifEmpty { handTracks.firstOrNull { it.frames.isNotEmpty() }?.frames.orEmpty() }
            .sortedBy { it.tMs }
    }

    private fun List<Float>.isValidHand(): Boolean {
        return size >= HAND_LANDMARK_COUNT * LANDMARK_VALUE_COUNT
    }

    companion object {
        const val BODY_WEIGHT = 0.85
        const val HAND_WEIGHT = 0.15
        private const val MATCH_FEEDBACK_THRESHOLD = 0.72
        private val MCP_INDICES = intArrayOf(5, 9, 13, 17)

        fun combineWithBodyScore(
            bodyScore: Double,
            handScore: Double?,
            hasReferenceHands: Boolean
        ): Double {
            return if (hasReferenceHands && handScore != null) {
                bodyScore * BODY_WEIGHT + handScore * HAND_WEIGHT
            } else {
                bodyScore
            }.coerceIn(0.0, 1.0)
        }
    }
}
