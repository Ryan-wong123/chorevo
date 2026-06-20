package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarker
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult

class HolisticLandmarkerHelper(
    context: Context,
    modelAssetPath: String = MODEL_ASSET,
    private val runningMode: RunningMode = RunningMode.IMAGE,
    minPoseDetectionConfidence: Float = 0.5f,
    minPosePresenceConfidence: Float = 0.5f,
    minHandLandmarksConfidence: Float = 0.5f
) : AutoCloseable {
    private val landmarker: HolisticLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .build()

        val options = HolisticLandmarker.HolisticLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setMinHandLandmarksConfidence(minHandLandmarksConfidence)
            .setOutputFaceBlendshapes(false)
            .setOutputPoseSegmentationMasks(false)
            .build()

        landmarker = HolisticLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): HolisticFrameLandmarks {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        return landmarker.detect(mpImage).toAppLandmarks()
    }

    fun detectForVideo(bitmap: Bitmap, timestampMs: Long): HolisticFrameLandmarks {
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        return landmarker.detectForVideo(mpImage, timestampMs).toAppLandmarks()
    }

    override fun close() {
        landmarker.close()
    }

    companion object {
        const val MODEL_ASSET: String = "holistic_landmarker.task"
    }
}

private fun HolisticLandmarkerResult.toAppLandmarks(): HolisticFrameLandmarks {
    return HolisticFrameLandmarks(
        pose = poseLandmarks().flattenPoseLandmarks(),
        leftHand = leftHandLandmarks().flattenHandLandmarks(),
        rightHand = rightHandLandmarks().flattenHandLandmarks()
    )
}
