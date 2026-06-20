package com.example.chorevo.posedetection

data class HolisticFrameLandmarks(
    val pose: List<Float> = emptyList(),
    val leftHand: List<Float> = emptyList(),
    val rightHand: List<Float> = emptyList()
)

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
