package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder

class PoseLandmarkerHelper(
    context: Context,
    modelAssetPath: String = "pose_landmarker_lite.task",
    private val runningMode: RunningMode = RunningMode.IMAGE,
    maxPoses: Int = 1,
    minPoseDetectionConfidence: Float = 0.5f,
    minPosePresenceConfidence: Float = 0.5f,
    minTrackingConfidence: Float = 0.5f,
    useGpu: Boolean = false
) {
    private val landmarker: PoseLandmarker

    init {
        fun create(delegate: Delegate): PoseLandmarker {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .setDelegate(delegate)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(runningMode)
                .setNumPoses(maxPoses.coerceAtLeast(1))
                .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                .setMinPosePresenceConfidence(minPosePresenceConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .build()

            return PoseLandmarker.createFromOptions(context, options)
        }

        // The GPU delegate is not available on every device; fall back to CPU.
        landmarker = (if (GpuSupport.canUseGpu(useGpu)) runCatching { create(Delegate.GPU) }.getOrNull() else null)
            ?: create(Delegate.CPU)
    }


    /** Handles detect. */
    fun detect(bitmap: Bitmap): PoseLandmarkerResult {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        return landmarker.detect(mpImage)
    }


    /** Handles detect for video. */
    fun detectForVideo(bitmap: Bitmap, timestampMs: Long): PoseLandmarkerResult {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        return landmarker.detectForVideo(mpImage, timestampMs)
    }

    /** Handles close. */
    fun close() {
        landmarker.close()
    }
}
