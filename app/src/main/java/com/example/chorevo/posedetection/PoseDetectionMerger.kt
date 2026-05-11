package com.example.chorevo.posedetection

import kotlin.math.hypot
import kotlin.math.max

object PoseDetectionMerger {
    private const val DUPLICATE_IOU_THRESHOLD = 0.45f
    private const val DUPLICATE_CENTER_DISTANCE = 0.08f

    fun mergeDuplicateLandmarkDetections(detections: List<List<Float>>): List<List<Float>> {
        val kept = mutableListOf<List<Float>>()

        for (candidate in detections.sortedByDescending(::landmarkDetectionPriority)) {
            val candidateBounds = candidate.poseBounds() ?: continue
            val overlapsExisting = kept.any { existing ->
                val existingBounds = existing.poseBounds() ?: return@any false
                existingBounds.iou(candidateBounds) > DUPLICATE_IOU_THRESHOLD ||
                    centerDistance(existingBounds, candidateBounds) < DUPLICATE_CENTER_DISTANCE
            }

            if (!overlapsExisting) {
                kept += candidate
            }
        }

        return kept
    }

    fun mergeDuplicateDetections(detections: List<DetectionBox>): List<DetectionBox> {
        val kept = mutableListOf<DetectionBox>()

        for (candidate in detections.sortedByDescending { it.area * max(it.confidence, 0.1f) }) {
            val overlapsExisting = kept.any { existing ->
                existing.bounds.iou(candidate.bounds) > DUPLICATE_IOU_THRESHOLD ||
                    centerDistance(existing.bounds, candidate.bounds) < DUPLICATE_CENTER_DISTANCE
            }

            if (!overlapsExisting) {
                kept += candidate
            }
        }

        return kept
    }

    private fun landmarkDetectionPriority(landmarks: List<Float>): Float {
        val bounds = landmarks.poseBounds() ?: return 0f
        return bounds.area * max(landmarks.averageVisibility(), 0.1f)
    }

    private fun centerDistance(first: PoseBounds, second: PoseBounds): Float {
        return hypot(
            (first.centerX - second.centerX).toDouble(),
            (first.centerY - second.centerY).toDouble()
        ).toFloat()
    }
}
