package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min


object VideoPoseTemplateGenerator {
    private const val MAX_TRACKED_DANCERS = 8
    private const val LITE_MODEL_ASSET = "pose_landmarker_lite.task"
    private const val FULL_MODEL_ASSET = "pose_landmarker_full.task"

    enum class GenerationProfile {
        STANDARD,
        HIGH_RECALL
    }

    private data class CropRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Handles generate. */
    suspend fun generate(
        context: Context,
        videoUriString: String,
        fps: Int = 5,
        maxDurationMs: Long = 120_000L, // safety cap: 2 minutes
        profile: GenerationProfile = GenerationProfile.STANDARD,
        onProgress: ((Float) -> Unit)? = null
    ): Result<PoseTemplate> {
        return withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            val highRecall = profile == GenerationProfile.HIGH_RECALL
            val modelAssetPath = preferredModelAsset(context = context, highRecall = highRecall)

            //  use VIDEO mode helper for video template generation
            val helper = PoseLandmarkerHelper(
                context = context,
                modelAssetPath = modelAssetPath,
                runningMode = RunningMode.VIDEO,
                maxPoses = MAX_TRACKED_DANCERS,
                minPoseDetectionConfidence = if (highRecall) 0.20f else 0.35f,
                minPosePresenceConfidence = if (highRecall) 0.20f else 0.35f,
                minTrackingConfidence = if (highRecall) 0.20f else 0.35f
            )
            val cropHelper = if (highRecall) {
                PoseLandmarkerHelper(
                    context = context,
                    modelAssetPath = modelAssetPath,
                    runningMode = RunningMode.IMAGE,
                    maxPoses = MAX_TRACKED_DANCERS,
                    minPoseDetectionConfidence = 0.20f,
                    minPosePresenceConfidence = 0.20f,
                    minTrackingConfidence = 0.20f
                )
            } else {
                null
            }

            try {
                retriever.setDataSource(context, Uri.parse(videoUriString))

                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: return@withContext Result.failure(IllegalStateException("Cannot read video duration"))

                val safeDurationMs = min(durationMs, maxDurationMs)
                val safeFps = if (highRecall) max(8, fps) else max(5, fps)
                val stepMs = 1000L / safeFps

                val trackingConfig = if (highRecall) {
                    PoseTrackingConfig.highRecall(safeFps)
                } else {
                    PoseTrackingConfig.standard()
                }
                val tracker = MultiPersonPoseTracker(
                    fps = safeFps,
                    config = trackingConfig
                )

                var tMs = 0L
                var idx = 0
                val totalSteps = max(1, (safeDurationMs / stepMs).toInt())

                while (tMs <= safeDurationMs) {
                    // getFrameAtTime expects microseconds
                    val bmp = retriever.getFrameAtTime(
                        tMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )

                    if (bmp != null) {
                        val processedBmp = if (highRecall) brightenBitmap(bmp, 1.25f) else bmp

                        // VIDEO running mode requires timestamped detect
                        val fullFrameDetections = helper.detectForVideo(processedBmp, tMs)
                            .toFlatDetections()
                        val cropDetections = cropHelper
                            ?.detectCropPasses(processedBmp)
                            .orEmpty()
                        val detections = PoseDetectionMerger.mergeDuplicateLandmarkDetections(
                            fullFrameDetections + cropDetections
                        )

                        tracker.addFrame(
                            tMs = tMs,
                            width = processedBmp.width,
                            height = processedBmp.height,
                            detections = detections
                        )

                        if (processedBmp !== bmp) {
                            processedBmp.recycle()
                        }
                    }

                    idx++
                    onProgress?.invoke(idx.toFloat() / totalSteps.toFloat())

                    tMs += stepMs
                }

                val template = tracker.buildTemplate()
                    ?: return@withContext Result.failure(
                        IllegalStateException("No dancers were detected in the uploaded video")
                    )

                Result.success(template)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
                try {
                    helper.close()
                } catch (_: Exception) {
                }
                try {
                    cropHelper?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun preferredModelAsset(context: Context, highRecall: Boolean): String {
        if (!highRecall) return LITE_MODEL_ASSET
        return if (assetExists(context, FULL_MODEL_ASSET)) FULL_MODEL_ASSET else LITE_MODEL_ASSET
    }

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult.toFlatDetections(): List<List<Float>> {
        return landmarks().mapNotNull { pose ->
            if (pose.isEmpty()) return@mapNotNull null
            val flat = ArrayList<Float>(pose.size * 4)
            for (lm in pose) {
                flat.add(lm.x())
                flat.add(lm.y())
                flat.add(lm.z())
                val vis = lm.visibility().orElse(1.0f)
                flat.add(vis)
            }
            flat
        }
    }

    private fun PoseLandmarkerHelper.detectCropPasses(frame: Bitmap): List<List<Float>> {
        val crops = listOf(
            CropRegion(0f, 0f, 1f, 1f),
            CropRegion(0f, 0f, 0.6f, 1f),
            CropRegion(0.2f, 0f, 0.8f, 1f),
            CropRegion(0.4f, 0f, 1f, 1f),
            CropRegion(0f, 0f, 1f, 0.82f)
        )

        return crops.flatMap { crop ->
            val cropBitmap = frame.cropTo(crop)
            try {
                detect(cropBitmap)
                    .toFlatDetections()
                    .map { landmarks -> remapCropLandmarks(landmarks, crop) }
            } finally {
                cropBitmap.recycle()
            }
        }
    }

    private fun Bitmap.cropTo(crop: CropRegion): Bitmap {
        val left = (crop.left * width).toInt().coerceIn(0, width - 1)
        val top = (crop.top * height).toInt().coerceIn(0, height - 1)
        val right = (crop.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (crop.bottom * height).toInt().coerceIn(top + 1, height)
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    private fun remapCropLandmarks(
        landmarks: List<Float>,
        crop: CropRegion
    ): List<Float> {
        val remapped = ArrayList<Float>(landmarks.size)

        for (i in landmarks.indices step 4) {
            val x = landmarks[i]
            val y = landmarks[i + 1]
            val z = landmarks[i + 2]
            val v = landmarks[i + 3]

            remapped.add(crop.left + x * crop.width)
            remapped.add(crop.top + y * crop.height)
            remapped.add(z)
            remapped.add(v)
        }

        return remapped
    }

    private fun brightenBitmap(src: Bitmap, factor: Float = 1.25f): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val width = bmp.width
        val height = bmp.height
        val pixels = IntArray(width * height)

        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]

            val a = color ushr 24
            var r = color shr 16 and 0xff
            var g = color shr 8 and 0xff
            var b = color and 0xff

            r = (r * factor).toInt().coerceIn(0, 255)
            g = (g * factor).toInt().coerceIn(0, 255)
            b = (b * factor).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
