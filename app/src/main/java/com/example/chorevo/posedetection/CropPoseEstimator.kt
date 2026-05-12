package com.example.chorevo.posedetection

import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class MediaPipeCropPoseEstimator(
    private val poseLandmarkerHelper: PoseLandmarkerHelper,
    private val config: CropPoseEstimatorConfig = CropPoseEstimatorConfig()
) : CropPoseEstimator {

    override fun estimate(
        frame: Bitmap,
        trackedDancers: List<TrackedDancerBox>
    ): CropPoseEstimationResult {
        if (trackedDancers.isEmpty()) return CropPoseEstimationResult()

        val poses = mutableListOf<EstimatedPoseFrame>()
        val warnings = linkedMapOf<String, PoseAnalysisWarning>()

        for (trackedDancer in trackedDancers.filter(::shouldEstimateCrop)) {
            val expandedBox = CropPoseGeometry
                .expandBox(trackedDancer.box, config.paddingFraction)
                .let(CropPoseGeometry::clampBox)

            if (
                expandedBox.width < config.minBoxWidth ||
                expandedBox.height < config.minBoxHeight
            ) {
                warnings.putIfAbsent(
                    WARNING_CROP_POSE_MISSING,
                    PoseAnalysisWarning(
                        code = WARNING_CROP_POSE_MISSING,
                        message = "Some tracked dancer crops were too small for reliable pose estimation."
                    )
                )
                continue
            }

            val cropRect = CropPoseGeometry.toPixelCropRect(
                box = expandedBox,
                frameWidth = frame.width,
                frameHeight = frame.height
            )
            if (!cropRect.isValid) {
                warnings.putIfAbsent(
                    WARNING_CROP_POSE_MISSING,
                    PoseAnalysisWarning(
                        code = WARNING_CROP_POSE_MISSING,
                        message = "Some tracked dancer crops were invalid and were treated as missing."
                    )
                )
                continue
            }

            val cropBitmap = try {
                Bitmap.createBitmap(
                    frame,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width,
                    cropRect.height
                )
            } catch (_: Exception) {
                warnings.putIfAbsent(
                    WARNING_CROP_POSE_MISSING,
                    PoseAnalysisWarning(
                        code = WARNING_CROP_POSE_MISSING,
                        message = "Some tracked dancer crops failed and were treated as missing."
                    )
                )
                null
            } ?: continue

            try {
                val remappedLandmarks = poseLandmarkerHelper
                    .detect(cropBitmap)
                    .toFlatDetections()
                    .map { landmarks ->
                        CropPoseGeometry.remapCropLandmarksToFullFrame(landmarks, expandedBox)
                    }

                val bestLandmarks = remappedLandmarks.maxByOrNull { landmarks ->
                    val bounds = landmarks.poseBounds(padding = 0f)
                    (bounds?.area ?: 0f) * landmarks.averageVisibility()
                }

                if (bestLandmarks == null) {
                    warnings.putIfAbsent(
                        WARNING_CROP_POSE_MISSING,
                        PoseAnalysisWarning(
                            code = WARNING_CROP_POSE_MISSING,
                            message = "Some tracked dancer crops produced no landmarks and were treated as missing."
                        )
                    )
                    continue
                }

                poses += EstimatedPoseFrame(
                    trackId = trackedDancer.trackId,
                    frame = PoseFrame(
                        tMs = trackedDancer.timestampMs,
                        landmarks = bestLandmarks
                    ),
                    bounds = expandedBox.bounds,
                    confidence = minOf(
                        trackedDancer.confidence,
                        bestLandmarks.averageVisibility()
                    )
                )
            } catch (_: Exception) {
                warnings.putIfAbsent(
                    WARNING_CROP_POSE_MISSING,
                    PoseAnalysisWarning(
                        code = WARNING_CROP_POSE_MISSING,
                        message = "Some tracked dancer crop pose estimations failed and were treated as missing."
                    )
                )
            } finally {
                cropBitmap.recycle()
            }
        }

        return CropPoseEstimationResult(
            poses = poses,
            warnings = warnings.values.toList()
        )
    }

    override fun close() {
        poseLandmarkerHelper.close()
    }

    private fun shouldEstimateCrop(trackedDancer: TrackedDancerBox): Boolean {
        return trackedDancer.state == TrackState.Visible ||
            trackedDancer.state == TrackState.Recovered
    }

    private fun PoseLandmarkerResult.toFlatDetections(): List<List<Float>> {
        return landmarks().mapNotNull { pose ->
            if (pose.isEmpty()) return@mapNotNull null
            val flat = ArrayList<Float>(pose.size * 4)
            for (landmark in pose) {
                flat.add(landmark.x())
                flat.add(landmark.y())
                flat.add(landmark.z())
                flat.add(landmark.visibility().orElse(1.0f))
            }
            flat
        }
    }

    companion object {
        const val WARNING_CROP_POSE_MISSING = "cropPoseMissing"
    }
}

object CropPoseGeometry {
    fun expandBox(box: DetectionBox, paddingFraction: Float): DetectionBox {
        val safePadding = paddingFraction.coerceAtLeast(0f)
        val padX = box.width * safePadding
        val padY = box.height * safePadding
        return DetectionBox.normalized(
            left = box.left - padX,
            top = box.top - padY,
            right = box.right + padX,
            bottom = box.bottom + padY,
            confidence = box.confidence,
            label = box.label,
            classId = box.classId,
            source = box.source
        )
    }

    fun clampBox(box: DetectionBox): DetectionBox {
        return DetectionBox.normalized(
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            confidence = box.confidence,
            label = box.label,
            classId = box.classId,
            source = box.source
        )
    }

    fun toPixelCropRect(
        box: DetectionBox,
        frameWidth: Int,
        frameHeight: Int
    ): PixelCropRect {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return PixelCropRect(left = 0, top = 0, width = 0, height = 0)
        }

        val clamped = clampBox(box)
        val left = floor(clamped.left * frameWidth).roundToInt().coerceIn(0, frameWidth - 1)
        val top = floor(clamped.top * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val right = ceil(clamped.right * frameWidth).roundToInt().coerceIn(left + 1, frameWidth)
        val bottom = ceil(clamped.bottom * frameHeight).roundToInt().coerceIn(top + 1, frameHeight)

        return PixelCropRect(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top
        )
    }

    fun remapCropLandmarksToFullFrame(
        landmarks: List<Float>,
        cropBox: DetectionBox
    ): List<Float> {
        val clampedCrop = clampBox(cropBox)
        val remapped = ArrayList<Float>(landmarks.size)

        for (index in landmarks.indices step 4) {
            val cropX = landmarks[index]
            val cropY = landmarks[index + 1]
            val z = landmarks[index + 2]
            val visibility = landmarks[index + 3]

            remapped += (clampedCrop.left + cropX * clampedCrop.width).coerceIn(0f, 1f)
            remapped += (clampedCrop.top + cropY * clampedCrop.height).coerceIn(0f, 1f)
            remapped += z
            remapped += visibility.coerceIn(0f, 1f)
        }

        return remapped
    }
}
