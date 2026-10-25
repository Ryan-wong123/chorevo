package com.example.chorevo.posedetection

import android.graphics.Bitmap

interface PersonDetector : AutoCloseable {
    suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox>
}

interface DancerTracker {
    fun update(
        frameIndex: Int,
        timestampMs: Long,
        detections: List<DetectionBox>
    ): List<TrackedDancerBox>

    fun tracks(): List<DancerTrack>
}

interface CropPoseEstimator : AutoCloseable {
    fun estimate(
        frame: Bitmap,
        trackedDancers: List<TrackedDancerBox>
    ): CropPoseEstimationResult
}

interface FramePreprocessor {
    fun process(frame: Bitmap, timestampMs: Long): FramePreprocessorResult
}

class NoOpPersonDetector : PersonDetector {
    override suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox> {
        return emptyList()
    }

    override fun close() = Unit
}

class NoOpCropPoseEstimator : CropPoseEstimator {
    override fun estimate(
        frame: Bitmap,
        trackedDancers: List<TrackedDancerBox>
    ): CropPoseEstimationResult {
        return CropPoseEstimationResult()
    }

    override fun close() = Unit
}

object NoOpFramePreprocessor : FramePreprocessor {
    override fun process(frame: Bitmap, timestampMs: Long): FramePreprocessorResult {
        return FramePreprocessorResult(bitmap = frame, ownsBitmap = false)
    }
}
