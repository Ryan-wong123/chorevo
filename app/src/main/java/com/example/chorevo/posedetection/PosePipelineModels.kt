package com.example.chorevo.posedetection

import android.graphics.Bitmap

enum class DetectionSource {
    MediaPipeFullFrame,
    MediaPipeCrop,
    Yolo,
    Unknown
}

data class DancerDetection(
    val bounds: PoseBounds,
    val confidence: Float,
    val classId: Int = PERSON_CLASS_ID,
    val label: String = PERSON_LABEL,
    val source: DetectionSource = DetectionSource.Unknown
) {
    companion object {
        const val PERSON_CLASS_ID = 0
        const val PERSON_LABEL = "person"
    }
}

enum class TrackState {
    Visible,
    TemporarilyLost,
    Recovered,
    Ended
}

data class TrackedDancerBox(
    val trackId: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val bounds: PoseBounds,
    val state: TrackState,
    val confidence: Float
)

data class DancerTrack(
    val trackId: String,
    val boxes: List<TrackedDancerBox> = emptyList()
)

data class EstimatedPoseFrame(
    val trackId: String,
    val frame: PoseFrame,
    val bounds: PoseBounds,
    val confidence: Float
)

data class PoseAnalysisWarning(
    val code: String,
    val message: String
)

data class FramePreprocessorResult(
    val bitmap: Bitmap,
    val ownsBitmap: Boolean = false,
    val warnings: List<PoseAnalysisWarning> = emptyList()
)

data class FramePreprocessorConfig(
    val enabled: Boolean = true,
    val brightness: Float = DEFAULT_BRIGHTNESS,
    val contrast: Float = DEFAULT_CONTRAST,
    val gamma: Float = DEFAULT_GAMMA,
    val lowLightWarningThreshold: Float = DEFAULT_LOW_LIGHT_WARNING_THRESHOLD
) {
    companion object {
        const val DEFAULT_BRIGHTNESS = 8f
        const val DEFAULT_CONTRAST = 1.08f
        const val DEFAULT_GAMMA = 1.10f
        const val DEFAULT_LOW_LIGHT_WARNING_THRESHOLD = 45f

        fun disabled(): FramePreprocessorConfig {
            return FramePreprocessorConfig(enabled = false)
        }
    }
}

data class PoseTrackingConfig(
    val minTrackFrames: Int,
    val maxMissedSteps: Int,
    val minAverageVisibility: Float,
    val minAverageArea: Float,
    val minTrackCoverage: Float
) {
    companion object {
        fun standard(): PoseTrackingConfig {
            return PoseTrackingConfig(
                minTrackFrames = 3,
                maxMissedSteps = 5,
                minAverageVisibility = 0.30f,
                minAverageArea = 0.004f,
                minTrackCoverage = 0.25f
            )
        }

        fun highRecall(fps: Int): PoseTrackingConfig {
            val safeFps = fps.coerceAtLeast(1)
            return PoseTrackingConfig(
                minTrackFrames = 3,
                maxMissedSteps = safeFps * 3,
                minAverageVisibility = 0.18f,
                minAverageArea = 0.002f,
                minTrackCoverage = 0.12f
            )
        }
    }
}
