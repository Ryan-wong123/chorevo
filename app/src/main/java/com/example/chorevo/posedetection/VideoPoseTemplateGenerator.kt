package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.chorevo.core.Database.di.DefaultDispatcher
import com.google.mediapipe.tasks.vision.core.RunningMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min


/**
 * Turns a dance video into a pose template with MediaPipe, on the phone.
 * CPU-heavy: runs on the injected default dispatcher.
 */
@Singleton
class VideoPoseTemplateGenerator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val MAX_TRACKED_DANCERS = 8
        private const val LITE_MODEL_ASSET = "pose_landmarker_lite.task"
        private const val FULL_MODEL_ASSET = "pose_landmarker_full.task"
        const val WARNING_HAND_EXTRACTION_PARTIAL = "handExtractionPartial"
    }

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
        videoUriString: String,
        fps: Int = 5,
        maxDurationMs: Long = 120_000L, // safety cap: 2 minutes
        profile: GenerationProfile = GenerationProfile.STANDARD,
        framePreprocessorConfig: FramePreprocessorConfig = FramePreprocessorConfig(),
        personDetectorConfig: PersonDetectorConfig = PersonDetectorConfig.disabled(),
        collectHandLandmarks: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Result<PoseTemplate> {
        val context = appContext
        return withContext(defaultDispatcher) {
            val retriever = MediaMetadataRetriever()
            val highRecall = profile == GenerationProfile.HIGH_RECALL
            val handEnabledHighRecall = highRecall && collectHandLandmarks
            val modelAssetPath = preferredModelAsset(context = context, highRecall = highRecall)
            val framePreprocessor = if (highRecall && framePreprocessorConfig.enabled) {
                ConservativeFramePreprocessor(framePreprocessorConfig)
            } else {
                NoOpFramePreprocessor
            }
            val analysisWarnings = linkedMapOf<String, PoseAnalysisWarning>()
            val effectivePersonDetectorConfig = if (collectHandLandmarks && !personDetectorConfig.enabled) {
                personDetectorConfig.copy(enabled = true)
            } else {
                personDetectorConfig
            }
            val personDetectorInitialization = if (highRecall) {
                PersonDetectorFactory.create(
                    context = context,
                    config = effectivePersonDetectorConfig
                )
            } else {
                PersonDetectorInitialization(detector = NoOpPersonDetector())
            }
            personDetectorInitialization.warning?.let { warning ->
                analysisWarnings.putIfAbsent(warning.code, warning)
            }
            val personDetector = personDetectorInitialization.detector

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
            val holisticVideoHelper = if (!highRecall && collectHandLandmarks) {
                runCatching {
                    HolisticLandmarkerHelper(
                        context = context,
                        runningMode = RunningMode.VIDEO,
                        minPoseDetectionConfidence = 0.30f,
                        minPosePresenceConfidence = 0.30f,
                        minHandLandmarksConfidence = 0.30f
                    )
                }.getOrElse {
                    analysisWarnings.putIfAbsent(
                        WARNING_HAND_EXTRACTION_PARTIAL,
                        PoseAnalysisWarning(
                            code = WARNING_HAND_EXTRACTION_PARTIAL,
                            message = "Hand extraction was unavailable; template generation used body-only pose landmarks."
                        )
                    )
                    null
                }
            } else {
                null
            }
            val cropHelper = if (highRecall && !handEnabledHighRecall) {
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
            val cropPoseEstimator: CropPoseEstimator = if (highRecall) {
                val fallbackCropEstimator = runCatching {
                    createBodyOnlyCropEstimator(context, modelAssetPath)
                }.getOrElse {
                    analysisWarnings.putIfAbsent(
                        MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING,
                        PoseAnalysisWarning(
                            code = MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING,
                            message = "Per-dancer crop pose estimation was unavailable; upload analysis used fallback MediaPipe passes."
                        )
                    )
                    NoOpCropPoseEstimator()
                }
                if (collectHandLandmarks) {
                    runCatching {
                        MediaPipeCropHolisticEstimator(
                            holisticLandmarkerHelper = HolisticLandmarkerHelper(
                                context = context,
                                runningMode = RunningMode.IMAGE,
                                minPoseDetectionConfidence = 0.20f,
                                minPosePresenceConfidence = 0.20f,
                                minHandLandmarksConfidence = 0.20f
                            ),
                            fallbackPoseEstimator = fallbackCropEstimator
                        )
                    }.getOrElse {
                        analysisWarnings.putIfAbsent(
                            WARNING_HAND_EXTRACTION_PARTIAL,
                            PoseAnalysisWarning(
                                code = WARNING_HAND_EXTRACTION_PARTIAL,
                                message = "Holistic crop extraction was unavailable; high-recall analysis used body-only crop pose estimation."
                            )
                        )
                        fallbackCropEstimator
                    }
                } else {
                    fallbackCropEstimator
                }
            } else {
                NoOpCropPoseEstimator()
            }

            // Hands are attached to dancers later by matching the pose they were detected with.
            val handObservations = mutableListOf<HandObservation>()

            fun recordHandFrame(pose: List<Float>, frame: HandLandmarkFrame) {
                if (pose.isEmpty()) return
                if (frame.leftHand.isEmpty() && frame.rightHand.isEmpty()) return
                handObservations += HandObservation(tMs = frame.tMs, pose = pose, hand = frame)
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
                val dancerTracker = ByteTrackDancerTracker(
                    config = DancerTrackerConfig(
                        maxMissedFrames = trackingConfig.maxMissedSteps,
                        maxTracks = MAX_TRACKED_DANCERS
                    )
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
                        val processedFrame = framePreprocessor.process(bmp, tMs)
                        val processedBmp = processedFrame.bitmap
                        processedFrame.warnings.forEach { warning ->
                            analysisWarnings.putIfAbsent(warning.code, warning)
                        }
                        val personDetections = runCatching {
                            PoseDetectionMerger.mergeDuplicateDetections(
                                personDetector.detect(processedBmp, tMs)
                                    .filter { detection ->
                                        detection.classId == effectivePersonDetectorConfig.personClassId
                                    }
                            )
                        }.getOrElse {
                            analysisWarnings.putIfAbsent(
                                PersonDetectorFactory.WARNING_PERSON_DETECTOR_UNAVAILABLE,
                                PoseAnalysisWarning(
                                    code = PersonDetectorFactory.WARNING_PERSON_DETECTOR_UNAVAILABLE,
                                    message = "Person detector failed during upload analysis"
                                )
                            )
                            emptyList()
                        }
                        val trackedDancerBoxes = dancerTracker.update(
                            frameIndex = idx,
                            timestampMs = tMs,
                            detections = personDetections
                        )
                        val trackedCropResult = cropPoseEstimator.estimate(
                            frame = processedBmp,
                            trackedDancers = trackedDancerBoxes
                        )
                        trackedCropResult.warnings.forEach { warning ->
                            analysisWarnings.putIfAbsent(warning.code, warning)
                        }
                        val cropPosesByTrackId = trackedCropResult.poses.associateBy { it.trackId }
                        trackedCropResult.handFrames.forEach { handFrame ->
                            cropPosesByTrackId[handFrame.trackId]?.let { cropPose ->
                                recordHandFrame(cropPose.frame.landmarks, handFrame.frame.copy(tMs = tMs))
                            }
                        }

                        // VIDEO running mode requires timestamped detect
                        val holisticFrame = if (!highRecall) {
                            holisticVideoHelper?.let { holisticHelper ->
                                runCatching {
                                    holisticHelper.detectForVideo(processedBmp, tMs)
                                }.getOrElse {
                                    analysisWarnings.putIfAbsent(
                                        WARNING_HAND_EXTRACTION_PARTIAL,
                                        PoseAnalysisWarning(
                                            code = WARNING_HAND_EXTRACTION_PARTIAL,
                                            message = "Some hand-enabled frame estimations failed and used body-only pose fallback."
                                        )
                                    )
                                    null
                                }
                            }
                        } else {
                            null
                        }
                        if (!highRecall && holisticFrame != null) {
                            recordHandFrame(
                                pose = holisticFrame.pose,
                                frame = HandLandmarkFrame(
                                    tMs = tMs,
                                    leftHand = holisticFrame.leftHand,
                                    rightHand = holisticFrame.rightHand
                                )
                            )
                            if (holisticFrame.pose.isEmpty()) {
                                analysisWarnings.putIfAbsent(
                                    WARNING_HAND_EXTRACTION_PARTIAL,
                                    PoseAnalysisWarning(
                                        code = WARNING_HAND_EXTRACTION_PARTIAL,
                                        message = "Some Holistic frames had missing body pose and used body-only pose fallback."
                                    )
                                )
                            }
                            if (holisticFrame.pose.isNotEmpty() &&
                                holisticFrame.leftHand.isEmpty() &&
                                holisticFrame.rightHand.isEmpty()
                            ) {
                                analysisWarnings.putIfAbsent(
                                    WARNING_HAND_EXTRACTION_PARTIAL,
                                    PoseAnalysisWarning(
                                        code = WARNING_HAND_EXTRACTION_PARTIAL,
                                        message = "Some hand-enabled frames had body pose but no hand landmarks."
                                    )
                                )
                            }
                        }
                        val trackedPoseDetections = trackedCropResult.poses.map { it.frame.landmarks }
                        val detections = if (handEnabledHighRecall && trackedPoseDetections.isNotEmpty()) {
                            trackedPoseDetections
                        } else {
                            val fullFrameDetections = holisticFrame
                                ?.pose
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { listOf(it) }
                                ?: helper.detectForVideo(processedBmp, tMs).toFlatDetections()
                            val cropDetections = cropHelper
                                ?.detectCropPasses(processedBmp)
                                .orEmpty()
                            PoseDetectionMerger.mergeDuplicateLandmarkDetections(
                                fullFrameDetections +
                                    cropDetections +
                                    trackedPoseDetections
                            )
                        }

                        tracker.addFrame(
                            tMs = tMs,
                            width = processedBmp.width,
                            height = processedBmp.height,
                            detections = detections
                        )

                        if (processedFrame.ownsBitmap) {
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
                val templateWithHands = template.copy(
                    handTracks = HandTrackAssigner.assign(template.availableTracks(), handObservations)
                )
                val processedTemplate = postProcessUploadedTemplate(
                    template = templateWithHands,
                    highRecall = highRecall,
                    fps = safeFps,
                    trackingConfig = trackingConfig,
                    analysisWarnings = analysisWarnings
                )

                Result.success(processedTemplate.withAdditionalWarnings(analysisWarnings.values))
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
                try {
                    cropPoseEstimator.close()
                } catch (_: Exception) {
                }
                try {
                    holisticVideoHelper?.close()
                } catch (_: Exception) {
                }
                try {
                    personDetector.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createBodyOnlyCropEstimator(
        context: Context,
        modelAssetPath: String
    ): CropPoseEstimator {
        return MediaPipeCropPoseEstimator(
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = context,
                modelAssetPath = modelAssetPath,
                runningMode = RunningMode.IMAGE,
                maxPoses = 1,
                minPoseDetectionConfidence = 0.20f,
                minPosePresenceConfidence = 0.20f,
                minTrackingConfidence = 0.20f
            )
        )
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

    private fun PoseTemplate.withAdditionalWarnings(
        additionalWarnings: Collection<PoseAnalysisWarning>
    ): PoseTemplate {
        if (additionalWarnings.isEmpty()) return this
        val warningsByCode = linkedMapOf<String, PoseAnalysisWarning>()
        qualityWarnings.forEach { warning ->
            warningsByCode.putIfAbsent(warning.code, warning)
        }
        additionalWarnings.forEach { warning ->
            warningsByCode.putIfAbsent(warning.code, warning)
        }
        return copy(qualityWarnings = warningsByCode.values.toList())
    }

    private fun postProcessUploadedTemplate(
        template: PoseTemplate,
        highRecall: Boolean,
        fps: Int,
        trackingConfig: PoseTrackingConfig,
        analysisWarnings: MutableMap<String, PoseAnalysisWarning>
    ): PoseTemplate {
        if (!highRecall || template.tracks.isEmpty()) return template

        return runCatching {
            val mergeResult = TrackFragmentMerger(
                fps = fps,
                config = TrackFragmentMergerConfig(
                    maxGapFrames = trackingConfig.maxMissedSteps,
                    maxGapDurationMs = 3_000L
                )
            ).merge(template.tracks)

            val interpolationResult = MissingLandmarkInterpolator(
                fps = fps,
                config = MissingLandmarkInterpolatorConfig(
                    maxGapFrames = 3,
                    maxGapDurationMs = 500L,
                    interpolatedVisibilityCap = 0.20f
                )
            ).interpolate(mergeResult.tracks)

            if (mergeResult.mergedTrackCount > 0) {
                analysisWarnings.putIfAbsent(
                    "mergedTrackFragments",
                    PoseAnalysisWarning(
                        code = "mergedTrackFragments",
                        message = "Some split dancer tracks were merged after upload analysis."
                    )
                )
            }
            if (interpolationResult.interpolatedFrameCount > 0) {
                analysisWarnings.putIfAbsent(
                    "inferredLowConfidenceLandmarks",
                    PoseAnalysisWarning(
                        code = "inferredLowConfidenceLandmarks",
                        message = "Short missing pose gaps were filled with low-confidence inferred landmarks."
                    )
                )
            }

            val tracks = interpolationResult.tracks
            val trackIds = tracks.map { it.trackId }.toSet()
            val defaultTrackId = mergeResult.replacedTrackIds[template.defaultTrackId]
                ?: template.defaultTrackId?.takeIf { it in trackIds }
                ?: tracks.maxWithOrNull(
                    compareBy<PoseTrack> { it.frames.size }
                        .thenBy { it.avgVisibility }
                )?.trackId

            val defaultFrames = tracks.firstOrNull { it.trackId == defaultTrackId }?.frames
                ?: tracks.firstOrNull()?.frames
                ?: template.frames

            template.copy(
                frames = defaultFrames,
                tracks = tracks,
                defaultTrackId = defaultTrackId,
                selectionPreview = template.selectionPreview
                    ?.withTrackReplacements(mergeResult.replacedTrackIds, trackIds),
                handTracks = template.handTracks.withTrackReplacements(
                    replacements = mergeResult.replacedTrackIds,
                    availableTrackIds = trackIds
                )
            )
        }.getOrElse {
            analysisWarnings.putIfAbsent(
                "trackCleanupSkipped",
                PoseAnalysisWarning(
                    code = "trackCleanupSkipped",
                    message = "Track cleanup was skipped; upload analysis kept the original detected tracks."
                )
            )
            template
        }
    }

    private fun PoseSelectionPreview.withTrackReplacements(
        replacements: Map<String, String>,
        availableTrackIds: Set<String>
    ): PoseSelectionPreview {
        val candidatesByTrack = candidates
            .mapNotNull { candidate ->
                val trackId = replacements[candidate.trackId] ?: candidate.trackId
                if (trackId !in availableTrackIds) return@mapNotNull null
                candidate.copy(trackId = trackId)
            }
            .groupBy { it.trackId }
            .map { (_, candidatesForTrack) ->
                candidatesForTrack.maxByOrNull { it.landmarks.averageVisibility() }
                    ?: candidatesForTrack.first()
            }

        return copy(candidates = candidatesByTrack)
    }

    private fun List<HandTrack>.withTrackReplacements(
        replacements: Map<String, String>,
        availableTrackIds: Set<String>
    ): List<HandTrack> {
        return mapNotNull { handTrack ->
            val trackId = replacements[handTrack.trackId] ?: handTrack.trackId
            if (trackId !in availableTrackIds) return@mapNotNull null
            handTrack.copy(trackId = trackId)
        }.groupBy { it.trackId }
            .map { (trackId, tracksForId) ->
                HandTrack(
                    trackId = trackId,
                    frames = tracksForId
                        .flatMap { it.frames }
                        .groupBy { it.tMs }
                        .map { (_, sameTimeFrames) ->
                            sameTimeFrames.maxByOrNull { frame ->
                                frame.leftHand.size + frame.rightHand.size
                            } ?: sameTimeFrames.first()
                        }
                        .sortedBy { it.tMs }
                )
            }
    }
}
