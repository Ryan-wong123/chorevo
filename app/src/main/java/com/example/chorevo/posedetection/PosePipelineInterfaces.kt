package com.example.chorevo.posedetection

import android.graphics.Bitmap

interface PersonDetector : AutoCloseable {
    suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox>
}

interface DancerTracker {
    fun update(
        frameIndex: Int,
        timestampMs: Long,
        detections: List<DancerDetection>
    ): List<TrackedDancerBox>

    fun tracks(): List<DancerTrack>
}

interface PoseEstimator : AutoCloseable {
    suspend fun estimate(
        frame: Bitmap,
        trackedDancers: List<TrackedDancerBox>
    ): List<EstimatedPoseFrame>
}

interface FramePreprocessor {
    fun process(frame: Bitmap, timestampMs: Long): FramePreprocessorResult
}

interface PoseTemplateRepository {
    suspend fun saveTemplate(danceId: String, template: PoseTemplate): Result<String>

    suspend fun loadTemplate(danceId: String, storagePath: String? = null): Result<PoseTemplate>
}

class NoOpPersonDetector : PersonDetector {
    override suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox> {
        return emptyList()
    }

    override fun close() = Unit
}

class NoOpPoseEstimator : PoseEstimator {
    override suspend fun estimate(
        frame: Bitmap,
        trackedDancers: List<TrackedDancerBox>
    ): List<EstimatedPoseFrame> {
        // TODO: Replace with crop-based MediaPipe pose estimation in a later phase.
        return emptyList()
    }

    override fun close() = Unit
}

object NoOpFramePreprocessor : FramePreprocessor {
    override fun process(frame: Bitmap, timestampMs: Long): FramePreprocessorResult {
        return FramePreprocessorResult(bitmap = frame, ownsBitmap = false)
    }
}
