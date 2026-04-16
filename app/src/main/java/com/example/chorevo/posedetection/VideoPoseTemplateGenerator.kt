package com.example.chorevo.posedetection

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min


object VideoPoseTemplateGenerator {
    private const val MAX_TRACKED_DANCERS = 6

    /** Handles generate. */
    suspend fun generate(
        context: Context,
        videoUriString: String,
        fps: Int = 2,
        maxDurationMs: Long = 120_000L, // safety cap: 2 minutes
        onProgress: ((Float) -> Unit)? = null
    ): Result<PoseTemplate> {
        return withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()

            //  use VIDEO mode helper for video template generation
            val helper = PoseLandmarkerHelper(
                context = context,
                runningMode = RunningMode.VIDEO,
                maxPoses = MAX_TRACKED_DANCERS,
                minPoseDetectionConfidence = 0.35f,
                minPosePresenceConfidence = 0.35f,
                minTrackingConfidence = 0.35f
            )

            try {
                retriever.setDataSource(context, Uri.parse(videoUriString))

                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?: return@withContext Result.failure(IllegalStateException("Cannot read video duration"))

                val safeDurationMs = min(durationMs, maxDurationMs)
                val safeFps = max(1, fps)
                val stepMs = 1000L / safeFps

                val tracker = MultiPersonPoseTracker(fps = safeFps)

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
                        // VIDEO running mode requires timestamped detect
                        val res = helper.detectForVideo(bmp, tMs)
                        val detections = res.landmarks()
                            .mapNotNull { pose ->
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

                        tracker.addFrame(
                            tMs = tMs,
                            width = bmp.width,
                            height = bmp.height,
                            detections = detections
                        )
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
            }
        }
    }
}
