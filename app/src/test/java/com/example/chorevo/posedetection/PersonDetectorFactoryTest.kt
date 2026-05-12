package com.example.chorevo.posedetection

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonDetectorFactoryTest {

    @Test
    fun detectionBox_normalizedClampsCoordinatesAndConfidence() {
        val box = DetectionBox.normalized(
            left = -0.2f,
            top = 1.2f,
            right = 0.8f,
            bottom = 0.1f,
            confidence = 1.4f,
            source = DetectionSource.Yolo
        )

        assertEquals(0f, box.left, 0.001f)
        assertEquals(0.1f, box.top, 0.001f)
        assertEquals(0.8f, box.right, 0.001f)
        assertEquals(1f, box.bottom, 0.001f)
        assertEquals(1f, box.confidence, 0.001f)
        assertEquals(DetectionSource.Yolo, box.source)
    }

    @Test
    fun noOpPersonDetector_returnsNoDetections() = runTest {
        val detector = NoOpPersonDetector()

        val detections = detector.detect(mockk<Bitmap>(relaxed = true), timestampMs = 100L)

        assertEquals(emptyList<DetectionBox>(), detections)
    }

    @Test
    fun create_disabledConfigReturnsNoOpWithoutWarning() {
        val result = PersonDetectorFactory.create(
            context = mockk(relaxed = true),
            config = PersonDetectorConfig.disabled()
        )

        assertTrue(result.detector is NoOpPersonDetector)
        assertNull(result.warning)
    }

    @Test
    fun create_missingModelAssetReturnsNoOpWithUnavailableWarning() {
        val result = PersonDetectorFactory.create(
            context = contextWithMissingAsset(),
            config = PersonDetectorConfig(enabled = true)
        )

        assertTrue(result.detector is NoOpPersonDetector)
        assertEquals(
            PersonDetectorFactory.WARNING_PERSON_DETECTOR_UNAVAILABLE,
            result.warning?.code
        )
    }

    @Test
    fun create_providerFailureFallsBackToNoOpWithWarning() {
        val result = PersonDetectorFactory.create(
            context = contextWithAvailableAsset(PersonDetectorConfig.DEFAULT_MODEL_ASSET),
            config = PersonDetectorConfig(enabled = true),
            detectorProvider = { error("Detector runtime failed") }
        )

        assertTrue(result.detector is NoOpPersonDetector)
        assertEquals(
            PersonDetectorFactory.WARNING_PERSON_DETECTOR_UNAVAILABLE,
            result.warning?.code
        )
    }

    private fun contextWithMissingAsset(): Context {
        val assetManager = mockk<AssetManager>()
        every { assetManager.open(any()) } throws FileNotFoundException("missing")

        val context = mockk<Context>()
        every { context.assets } returns assetManager
        return context
    }

    private fun contextWithAvailableAsset(assetPath: String): Context {
        val assetManager = mockk<AssetManager>()
        every { assetManager.open(assetPath) } returns ByteArrayInputStream(ByteArray(1))

        val context = mockk<Context>()
        every { context.assets } returns assetManager
        return context
    }
}
