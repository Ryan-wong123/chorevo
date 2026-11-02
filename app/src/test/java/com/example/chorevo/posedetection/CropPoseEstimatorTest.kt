package com.example.chorevo.posedetection

import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the MediaPipe crop estimators' control flow (state filtering, too-small boxes, error and
 * fallback handling, warning de-duplication). Real landmark extraction and bitmap decoding need
 * Android/MediaPipe natives, so Bitmap.createBitmap and the helpers are mocked.
 */
class CropPoseEstimatorTest {

    private lateinit var frame: Bitmap
    private lateinit var cropBitmap: Bitmap

    @Before
    fun setUp() {
        frame = mockk(relaxed = true)
        cropBitmap = mockk(relaxed = true)
        every { frame.width } returns 1000
        every { frame.height } returns 2000
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------
    // MediaPipeCropPoseEstimator
    // ------------------------------------------------------------

    @Test
    fun poseEstimator_noDancers_returnsEmptyResultWithoutDetecting() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(frame, emptyList())

        assertTrue(result.poses.isEmpty())
        assertTrue(result.handFrames.isEmpty())
        assertTrue(result.warnings.isEmpty())
        verify(exactly = 0) { helper.detect(any()) }
    }

    @Test
    fun poseEstimator_skipsTemporarilyLostAndEndedDancers() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(
            frame,
            listOf(
                tracked("track_1", TrackState.TemporarilyLost),
                tracked("track_2", TrackState.Ended)
            )
        )

        assertTrue(result.poses.isEmpty())
        assertTrue(result.warnings.isEmpty())
        verify(exactly = 0) { helper.detect(any()) }
    }

    @Test
    fun poseEstimator_tooSmallBox_isReportedAsMissing_withoutDetecting() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(
            frame,
            listOf(tracked("track_1", TrackState.Visible, box = tinyBox()))
        )

        assertTrue(result.poses.isEmpty())
        assertEquals(
            listOf(MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING),
            result.warnings.map { it.code }
        )
        verify(exactly = 0) { helper.detect(any()) }
    }

    @Test
    fun poseEstimator_manyTooSmallBoxes_produceSingleWarning() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(
            frame,
            listOf(
                tracked("track_1", TrackState.Visible, box = tinyBox()),
                tracked("track_2", TrackState.Recovered, box = tinyBox())
            )
        )

        assertEquals(1, result.warnings.size)
    }

    @Test
    fun poseEstimator_customMinBoxSize_rejectsOtherwiseNormalBox() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(
            helper,
            CropPoseEstimatorConfig(minBoxWidth = 0.95f, minBoxHeight = 0.95f)
        )

        val result = estimator.estimate(frame, listOf(tracked("track_1", TrackState.Visible)))

        assertTrue(result.poses.isEmpty())
        assertEquals(1, result.warnings.size)
        verify(exactly = 0) { helper.detect(any()) }
    }

    @Test
    fun poseEstimator_cropBitmapCreationFails_isReportedAsMissing() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } throws
            IllegalArgumentException("bad crop")
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(frame, listOf(tracked("track_1", TrackState.Visible)))

        assertTrue(result.poses.isEmpty())
        assertEquals(
            listOf(MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING),
            result.warnings.map { it.code }
        )
        verify(exactly = 0) { helper.detect(any()) }
    }

    @Test
    fun poseEstimator_noLandmarksDetected_isReportedAsMissing_andCropIsRecycled() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val result0 = mockk<PoseLandmarkerResult>()
        every { result0.landmarks() } returns emptyList()
        val helper = mockk<PoseLandmarkerHelper>()
        every { helper.detect(cropBitmap) } returns result0
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(frame, listOf(tracked("track_1", TrackState.Visible)))

        assertTrue(result.poses.isEmpty())
        assertEquals(
            listOf(MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING),
            result.warnings.map { it.code }
        )
        verify(exactly = 1) { cropBitmap.recycle() }
    }

    @Test
    fun poseEstimator_detectorThrows_isReportedAsMissing_andCropIsRecycled() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val helper = mockk<PoseLandmarkerHelper>()
        every { helper.detect(any()) } throws IllegalStateException("landmarker closed")
        val estimator = MediaPipeCropPoseEstimator(helper)

        val result = estimator.estimate(frame, listOf(tracked("track_1", TrackState.Visible)))

        assertTrue(result.poses.isEmpty())
        assertEquals(1, result.warnings.size)
        assertEquals(MediaPipeCropPoseEstimator.WARNING_CROP_POSE_MISSING, result.warnings.single().code)
        verify(exactly = 1) { cropBitmap.recycle() }
    }

    @Test
    fun poseEstimator_close_closesTheHelper() {
        val helper = mockk<PoseLandmarkerHelper>(relaxed = true)

        MediaPipeCropPoseEstimator(helper).close()

        verify(exactly = 1) { helper.close() }
    }

    // ------------------------------------------------------------
    // MediaPipeCropHolisticEstimator
    // ------------------------------------------------------------

    @Test
    fun holisticEstimator_noDancers_returnsEmptyWithoutCallingFallback() {
        val holistic = mockk<HolisticLandmarkerHelper>(relaxed = true)
        val fallback = mockk<CropPoseEstimator>(relaxed = true)
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, emptyList())

        assertTrue(result.poses.isEmpty())
        assertTrue(result.handFrames.isEmpty())
        assertTrue(result.warnings.isEmpty())
        verify(exactly = 0) { fallback.estimate(any(), any()) }
        verify(exactly = 0) { holistic.detect(any()) }
    }

    @Test
    fun holisticEstimator_inactiveDancers_areNotEstimated_andFallbackGetsEmptyList() {
        val holistic = mockk<HolisticLandmarkerHelper>(relaxed = true)
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(any(), any()) } returns CropPoseEstimationResult()
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(
            frame,
            listOf(tracked("track_1", TrackState.TemporarilyLost), tracked("track_2", TrackState.Ended))
        )

        assertTrue(result.poses.isEmpty())
        verify(exactly = 0) { holistic.detect(any()) }
        verify(exactly = 1) { fallback.estimate(frame, emptyList()) }
    }

    @Test
    fun holisticEstimator_tooSmallBox_usesFallback_andWarns() {
        val holistic = mockk<HolisticLandmarkerHelper>(relaxed = true)
        val dancer = tracked("track_1", TrackState.Visible, box = tinyBox())
        val fallbackPose = estimatedPose("track_1", tMs = 500L)
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(frame, listOf(dancer)) } returns
            CropPoseEstimationResult(poses = listOf(fallbackPose))
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, listOf(dancer))

        assertEquals(listOf(fallbackPose), result.poses)
        assertTrue(result.handFrames.isEmpty())
        assertEquals(
            listOf(MediaPipeCropHolisticEstimator.WARNING_CROP_HAND_MISSING),
            result.warnings.map { it.code }
        )
        verify(exactly = 0) { holistic.detect(any()) }
    }

    @Test
    fun holisticEstimator_success_remapsPoseAndHands_withoutFallbackDancers() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val holistic = mockk<HolisticLandmarkerHelper>()
        every { holistic.detect(cropBitmap) } returns HolisticFrameLandmarks(
            pose = listOf(0.5f, 0.5f, 0f, 0.8f),
            leftHand = listOf(0.1f, 0.2f, 0f, 1f),
            rightHand = emptyList()
        )
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(any(), any()) } returns CropPoseEstimationResult()
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)
        // Box 0.2..0.6 x 0.2..0.8 with 0.32 hand padding expands to 0.072..0.728 x 0.008..0.992.
        val dancer = tracked(
            "track_1",
            TrackState.Visible,
            timestampMs = 1_234L,
            box = DetectionBox.normalized(0.2f, 0.2f, 0.6f, 0.8f, confidence = 0.9f),
            confidence = 0.9f
        )

        val result = estimator.estimate(frame, listOf(dancer))

        assertEquals(1, result.poses.size)
        val pose = result.poses.single()
        assertEquals("track_1", pose.trackId)
        assertEquals(1_234L, pose.frame.tMs)
        assertEquals(0.4f, pose.frame.landmarks[0], 1e-4f)
        assertEquals(0.5f, pose.frame.landmarks[1], 1e-4f)
        assertEquals(0.8f, pose.frame.landmarks[3], 1e-4f)
        assertEquals(0.8f, pose.confidence, 1e-4f) // min(track confidence 0.9, avg visibility 0.8)
        assertEquals(1, result.handFrames.size)
        assertEquals("track_1", result.handFrames.single().trackId)
        assertEquals(1_234L, result.handFrames.single().frame.tMs)
        assertEquals(4, result.handFrames.single().frame.leftHand.size)
        assertTrue(result.handFrames.single().frame.rightHand.isEmpty())
        assertTrue(result.warnings.isEmpty())
        verify(exactly = 1) { fallback.estimate(frame, emptyList()) }
        verify(exactly = 1) { cropBitmap.recycle() }
    }

    @Test
    fun holisticEstimator_poseWithoutHands_keepsPoseAndWarnsAboutMissingHands() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val holistic = mockk<HolisticLandmarkerHelper>()
        every { holistic.detect(cropBitmap) } returns HolisticFrameLandmarks(
            pose = listOf(0.5f, 0.5f, 0f, 1f)
        )
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(any(), any()) } returns CropPoseEstimationResult()
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, listOf(tracked("track_1", TrackState.Recovered)))

        assertEquals(1, result.poses.size)
        assertTrue(result.handFrames.isEmpty())
        assertEquals(
            listOf(MediaPipeCropHolisticEstimator.WARNING_CROP_HAND_MISSING),
            result.warnings.map { it.code }
        )
        assertEquals(
            "Some tracked dancer crops had body pose but no hand landmarks.",
            result.warnings.single().message
        )
    }

    @Test
    fun holisticEstimator_emptyBodyPose_fallsBackToBodyOnlyEstimator() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val holistic = mockk<HolisticLandmarkerHelper>()
        every { holistic.detect(cropBitmap) } returns HolisticFrameLandmarks()
        val dancer = tracked("track_1", TrackState.Visible)
        val fallbackPose = estimatedPose("track_1", tMs = 0L)
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(frame, listOf(dancer)) } returns CropPoseEstimationResult(
            poses = listOf(fallbackPose),
            warnings = listOf(PoseAnalysisWarning("fallbackWarning", "from fallback"))
        )
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, listOf(dancer))

        assertEquals(listOf(fallbackPose), result.poses)
        assertTrue(result.handFrames.isEmpty())
        assertEquals(
            setOf(MediaPipeCropHolisticEstimator.WARNING_CROP_HAND_MISSING, "fallbackWarning"),
            result.warnings.map { it.code }.toSet()
        )
        verify(exactly = 1) { cropBitmap.recycle() }
    }

    @Test
    fun holisticEstimator_detectorThrows_fallsBackToBodyOnlyEstimator() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } returns cropBitmap
        val holistic = mockk<HolisticLandmarkerHelper>()
        every { holistic.detect(any()) } throws IllegalStateException("holistic failed")
        val dancer = tracked("track_1", TrackState.Visible)
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(frame, listOf(dancer)) } returns CropPoseEstimationResult()
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, listOf(dancer))

        assertTrue(result.poses.isEmpty())
        assertEquals(
            listOf(MediaPipeCropHolisticEstimator.WARNING_CROP_HAND_MISSING),
            result.warnings.map { it.code }
        )
        verify(exactly = 1) { fallback.estimate(frame, listOf(dancer)) }
        verify(exactly = 1) { cropBitmap.recycle() }
    }

    @Test
    fun holisticEstimator_cropBitmapFails_fallsBackWithoutRecycling() {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Bitmap>(), any(), any(), any(), any()) } throws
            IllegalArgumentException("bad crop")
        val holistic = mockk<HolisticLandmarkerHelper>(relaxed = true)
        val dancer = tracked("track_1", TrackState.Visible)
        val fallback = mockk<CropPoseEstimator>()
        every { fallback.estimate(frame, listOf(dancer)) } returns CropPoseEstimationResult()
        val estimator = MediaPipeCropHolisticEstimator(holistic, fallback)

        val result = estimator.estimate(frame, listOf(dancer))

        assertEquals(1, result.warnings.size)
        verify(exactly = 0) { holistic.detect(any()) }
        verify(exactly = 1) { fallback.estimate(frame, listOf(dancer)) }
    }

    @Test
    fun holisticEstimator_close_closesBothEvenWhenOneThrows() {
        val holistic = mockk<HolisticLandmarkerHelper>()
        every { holistic.close() } throws IllegalStateException("already closed")
        val fallback = mockk<CropPoseEstimator>(relaxed = true)

        MediaPipeCropHolisticEstimator(holistic, fallback).close()

        verify(exactly = 1) { holistic.close() }
        verify(exactly = 1) { fallback.close() }
    }

    // ------------------------------------------------------------
    // CropPoseGeometry extras
    // ------------------------------------------------------------

    @Test
    fun geometry_expandBox_negativePaddingIsTreatedAsZero() {
        val box = DetectionBox.normalized(0.2f, 0.2f, 0.6f, 0.8f, confidence = 0.5f)

        val expanded = CropPoseGeometry.expandBox(box, paddingFraction = -1f)

        assertEquals(0.2f, expanded.left, 1e-6f)
        assertEquals(0.6f, expanded.right, 1e-6f)
        assertEquals(0.2f, expanded.top, 1e-6f)
        assertEquals(0.8f, expanded.bottom, 1e-6f)
    }

    @Test
    fun geometry_expandBox_preservesMetadata() {
        val box = DetectionBox.normalized(
            0.2f, 0.2f, 0.6f, 0.8f,
            confidence = 0.7f,
            label = "person",
            classId = 3,
            source = DetectionSource.Yolo
        )

        val expanded = CropPoseGeometry.expandBox(box, 0.1f)

        assertEquals(0.7f, expanded.confidence, 1e-6f)
        assertEquals(3, expanded.classId)
        assertEquals(DetectionSource.Yolo, expanded.source)
    }

    @Test
    fun geometry_toPixelCropRect_nonPositiveFrame_isInvalid() {
        val box = DetectionBox.normalized(0.1f, 0.1f, 0.5f, 0.5f, confidence = 1f)

        assertTrue(!CropPoseGeometry.toPixelCropRect(box, 0, 100).isValid)
        assertTrue(!CropPoseGeometry.toPixelCropRect(box, 100, -1).isValid)
    }

    @Test
    fun geometry_toPixelCropRect_fullFrameBox_coversWholeFrame() {
        val box = DetectionBox.normalized(0f, 0f, 1f, 1f, confidence = 1f)

        val rect = CropPoseGeometry.toPixelCropRect(box, frameWidth = 640, frameHeight = 480)

        assertEquals(PixelCropRect(left = 0, top = 0, width = 640, height = 480), rect)
        assertEquals(640, rect.right)
        assertEquals(480, rect.bottom)
    }

    @Test
    fun geometry_toPixelCropRect_degenerateBox_stillHasAtLeastOnePixel() {
        val box = DetectionBox.normalized(0.5f, 0.5f, 0.5f, 0.5f, confidence = 1f)

        val rect = CropPoseGeometry.toPixelCropRect(box, frameWidth = 100, frameHeight = 100)

        assertTrue(rect.isValid)
        assertEquals(1, rect.width)
        assertEquals(1, rect.height)
    }

    @Test
    fun geometry_remapCropLandmarks_clampsVisibilityAndKeepsZ() {
        val cropBox = DetectionBox.normalized(0.5f, 0.5f, 1.0f, 1.0f, confidence = 1f)
        val landmarks = listOf(0f, 0f, 0.25f, 1.7f, 1f, 1f, -0.4f, -0.2f)

        val remapped = CropPoseGeometry.remapCropLandmarksToFullFrame(landmarks, cropBox)

        assertEquals(listOf(0.5f, 0.5f, 0.25f, 1f, 1f, 1f, -0.4f, 0f), remapped)
    }

    @Test
    fun geometry_remapCropLandmarks_emptyInput_returnsEmpty() {
        val cropBox = DetectionBox.normalized(0.1f, 0.1f, 0.9f, 0.9f, confidence = 1f)

        assertTrue(CropPoseGeometry.remapCropLandmarksToFullFrame(emptyList(), cropBox).isEmpty())
    }

    @Test
    fun cropEstimatorConfig_defaults_matchConstants() {
        val config = CropPoseEstimatorConfig()

        assertEquals(CropPoseEstimatorConfig.DEFAULT_PADDING_FRACTION, config.paddingFraction, 0f)
        assertEquals(CropPoseEstimatorConfig.DEFAULT_HAND_PADDING_FRACTION, config.handPaddingFraction, 0f)
        assertEquals(CropPoseEstimatorConfig.DEFAULT_MIN_BOX_WIDTH, config.minBoxWidth, 0f)
        assertEquals(CropPoseEstimatorConfig.DEFAULT_MIN_BOX_HEIGHT, config.minBoxHeight, 0f)
        assertTrue(config.handPaddingFraction > config.paddingFraction)
    }

    @Test
    fun noOpCropPoseEstimator_returnsEmptyResult() {
        val result = NoOpCropPoseEstimator().estimate(frame, listOf(tracked("t", TrackState.Visible)))

        assertTrue(result.poses.isEmpty())
        assertTrue(result.handFrames.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private fun tinyBox(): DetectionBox =
        DetectionBox.normalized(0.50f, 0.50f, 0.51f, 0.51f, confidence = 0.9f)

    private fun tracked(
        id: String,
        state: TrackState,
        timestampMs: Long = 0L,
        box: DetectionBox = DetectionBox.normalized(0.2f, 0.2f, 0.6f, 0.8f, confidence = 0.9f),
        confidence: Float = 0.9f
    ) = TrackedDancerBox(
        trackId = id,
        frameIndex = 0,
        timestampMs = timestampMs,
        box = box,
        state = state,
        confidence = confidence
    )

    private fun estimatedPose(id: String, tMs: Long) = EstimatedPoseFrame(
        trackId = id,
        frame = PoseFrame(tMs = tMs, landmarks = listOf(0.5f, 0.5f, 0f, 1f)),
        bounds = PoseBounds(0.1f, 0.1f, 0.9f, 0.9f),
        confidence = 0.7f
    )
}
