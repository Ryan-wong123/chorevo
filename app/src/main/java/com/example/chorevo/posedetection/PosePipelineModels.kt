package com.example.chorevo.posedetection

import android.graphics.Bitmap

enum class DetectionSource {
    MediaPipeFullFrame,
    MediaPipeCrop,
    Yolo,
    NoOp,
    Unknown
}

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val label: String = PERSON_LABEL,
    val classId: Int = PERSON_CLASS_ID,
    val source: DetectionSource = DetectionSource.Unknown
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)

    val area: Float
        get() = width * height

    val bounds: PoseBounds
        get() = PoseBounds(left = left, top = top, right = right, bottom = bottom)

    fun toDancerDetection(): DancerDetection {
        return DancerDetection(
            bounds = bounds,
            confidence = confidence,
            classId = classId,
            label = label,
            source = source
        )
    }

    companion object {
        const val PERSON_CLASS_ID = 0
        const val PERSON_LABEL = "person"

        fun normalized(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            confidence: Float,
            label: String = PERSON_LABEL,
            classId: Int = PERSON_CLASS_ID,
            source: DetectionSource = DetectionSource.Unknown
        ): DetectionBox {
            val clampedLeft = left.coerceIn(0f, 1f)
            val clampedTop = top.coerceIn(0f, 1f)
            val clampedRight = right.coerceIn(0f, 1f)
            val clampedBottom = bottom.coerceIn(0f, 1f)
            return DetectionBox(
                left = minOf(clampedLeft, clampedRight),
                top = minOf(clampedTop, clampedBottom),
                right = maxOf(clampedLeft, clampedRight),
                bottom = maxOf(clampedTop, clampedBottom),
                confidence = confidence.coerceIn(0f, 1f),
                label = label,
                classId = classId,
                source = source
            )
        }
    }
}

data class DancerDetection(
    val bounds: PoseBounds,
    val confidence: Float,
    val classId: Int = DetectionBox.PERSON_CLASS_ID,
    val label: String = DetectionBox.PERSON_LABEL,
    val source: DetectionSource = DetectionSource.Unknown
) {
    fun toDetectionBox(): DetectionBox {
        return DetectionBox.normalized(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            confidence = confidence,
            label = label,
            classId = classId,
            source = source
        )
    }
}

data class PersonDetectorConfig(
    val enabled: Boolean = false,
    val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    val inputSize: Int = DEFAULT_INPUT_SIZE,
    val personClassId: Int = DetectionBox.PERSON_CLASS_ID,
    val outputFormat: YoloOutputFormat = YoloOutputFormat.AUTO,
    val outputIncludesObjectness: Boolean = false
) {
    companion object {
        const val DEFAULT_MODEL_ASSET = "person_detector_yolo.tflite"
        const val DEFAULT_MIN_CONFIDENCE = 0.25f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val DEFAULT_INPUT_SIZE = 640

        fun disabled(): PersonDetectorConfig {
            return PersonDetectorConfig(enabled = false)
        }
    }
}

enum class YoloOutputFormat {
    AUTO,
    BOXES_FIRST,
    ATTRIBUTES_FIRST
}

data class PersonDetectorInitialization(
    val detector: PersonDetector,
    val warning: PoseAnalysisWarning? = null
)

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
    val box: DetectionBox,
    val state: TrackState,
    val confidence: Float
) {
    val bounds: PoseBounds
        get() = box.bounds
}

data class DancerTrackerConfig(
    val iouMatchThreshold: Float = DEFAULT_IOU_MATCH_THRESHOLD,
    val lowConfidenceThreshold: Float = DEFAULT_LOW_CONFIDENCE_THRESHOLD,
    val highConfidenceThreshold: Float = DEFAULT_HIGH_CONFIDENCE_THRESHOLD,
    val maxMissedFrames: Int = DEFAULT_MAX_MISSED_FRAMES,
    val maxTracks: Int = DEFAULT_MAX_TRACKS
) {
    companion object {
        const val DEFAULT_IOU_MATCH_THRESHOLD = 0.30f
        const val DEFAULT_LOW_CONFIDENCE_THRESHOLD = 0.10f
        const val DEFAULT_HIGH_CONFIDENCE_THRESHOLD = 0.50f
        const val DEFAULT_MAX_MISSED_FRAMES = 24
        const val DEFAULT_MAX_TRACKS = 8
    }
}

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
