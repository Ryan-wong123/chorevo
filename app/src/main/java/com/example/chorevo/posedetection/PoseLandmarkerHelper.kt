package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder

class PoseLandmarkerHelper(
    context: Context,
    modelAssetPath: String = "pose_landmarker_lite.task",
    private val runningMode: RunningMode = RunningMode.IMAGE
) {
    private val landmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
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
