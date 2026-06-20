package com.example.chorevo.posedetection

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

const val POSE_LANDMARK_COUNT: Int = 33
const val HAND_LANDMARK_COUNT: Int = 21
const val LANDMARK_VALUE_COUNT: Int = 4

data class HolisticFrameLandmarks(
    val pose: List<Float> = emptyList(),
    val leftHand: List<Float> = emptyList(),
    val rightHand: List<Float> = emptyList()
)

fun List<NormalizedLandmark>.flattenPoseLandmarks(): List<Float> {
    return flattenLandmarks(expectedLandmarkCount = POSE_LANDMARK_COUNT)
}

fun List<NormalizedLandmark>.flattenHandLandmarks(): List<Float> {
    return flattenLandmarks(expectedLandmarkCount = HAND_LANDMARK_COUNT)
}

fun List<NormalizedLandmark>.flattenLandmarks(expectedLandmarkCount: Int? = null): List<Float> {
    if (isEmpty()) return emptyList()
    if (expectedLandmarkCount != null && size != expectedLandmarkCount) return emptyList()

    val flat = ArrayList<Float>(size * LANDMARK_VALUE_COUNT)
    for (landmark in this) {
        flat.add(landmark.x())
        flat.add(landmark.y())
        flat.add(landmark.z())
        flat.add(landmark.visibility().orElse(1.0f))
    }
    return flat
}

object HolisticFrameSelector {
    fun primaryPose(frames: List<HolisticFrameLandmarks>): HolisticFrameLandmarks? {
        return frames
            .filter { it.pose.isNotEmpty() }
            .maxByOrNull { frame ->
                val prominence = frame.pose.poseBounds()?.area ?: 0f
                prominence * maxOf(frame.pose.averageVisibility(), 0.1f)
            }
    }
}
