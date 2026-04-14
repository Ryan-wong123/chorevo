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
                runningMode = RunningMode.VIDEO
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

                val frames = ArrayList<PoseFrame>()

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
                        val pose = res.landmarks().firstOrNull()

                        if (!pose.isNullOrEmpty()) {
                            val flat = ArrayList<Float>(pose.size * 4)
                            for (lm in pose) {
                                flat.add(lm.x())
                                flat.add(lm.y())
                                flat.add(lm.z())
                                val vis = lm.visibility().orElse(1.0f)
                                flat.add(vis)
                            }

                            frames.add(
                                PoseFrame(
                                    tMs = tMs,
                                    landmarks = flat
                                )
                            )
                        }
                    }

                    idx++
                    onProgress?.invoke(idx.toFloat() / totalSteps.toFloat())

                    tMs += stepMs
                }

                Result.success(
                    PoseTemplate(
                        fps = safeFps,
                        frames = frames
                    )
                )
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
