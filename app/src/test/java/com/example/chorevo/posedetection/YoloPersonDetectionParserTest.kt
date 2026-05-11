package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloPersonDetectionParserTest {

    @Test
    fun parse_boxesFirstOutputReturnsOnlyConfidentPersonDetections() {
        val output = arrayOf(
            arrayOf(
                floatArrayOf(0.5f, 0.5f, 0.4f, 0.2f, 0.80f, 0.10f),
                floatArrayOf(0.3f, 0.3f, 0.2f, 0.2f, 0.20f, 0.95f),
                floatArrayOf(0.7f, 0.7f, 0.2f, 0.2f, 0.10f, 0.10f)
            )
        )

        val detections = YoloPersonDetectionParser.parse(
            rawOutput = output,
            config = PersonDetectorConfig(
                enabled = true,
                minConfidence = 0.50f,
                outputFormat = YoloOutputFormat.BOXES_FIRST
            )
        )

        assertEquals(1, detections.size)
        val detection = detections.single()
        assertEquals(0.3f, detection.left, 0.001f)
        assertEquals(0.4f, detection.top, 0.001f)
        assertEquals(0.7f, detection.right, 0.001f)
        assertEquals(0.6f, detection.bottom, 0.001f)
        assertEquals(0.80f, detection.confidence, 0.001f)
        assertEquals(DetectionSource.Yolo, detection.source)
        assertEquals(DetectionBox.PERSON_CLASS_ID, detection.classId)
    }

    @Test
    fun parse_yoloV5ObjectnessOutputMultipliesObjectnessAndClassScore() {
        val output = arrayOf(
            arrayOf(
                floatArrayOf(0.5f, 0.5f, 0.4f, 0.2f, 0.90f, 0.80f, 0.10f)
            )
        )

        val detections = YoloPersonDetectionParser.parse(
            rawOutput = output,
            config = PersonDetectorConfig(
                enabled = true,
                minConfidence = 0.50f,
                outputFormat = YoloOutputFormat.BOXES_FIRST,
                outputIncludesObjectness = true
            )
        )

        assertEquals(1, detections.size)
        assertEquals(0.72f, detections.single().confidence, 0.001f)
    }

    @Test
    fun parse_attributesFirstOutputTransposesYoloV8Shape() {
        val output = arrayOf(
            arrayOf(
                floatArrayOf(0.5f, 0.2f),
                floatArrayOf(0.5f, 0.2f),
                floatArrayOf(0.4f, 0.2f),
                floatArrayOf(0.4f, 0.2f),
                floatArrayOf(0.80f, 0.10f)
            )
        )

        val detections = YoloPersonDetectionParser.parse(
            rawOutput = output,
            config = PersonDetectorConfig(
                enabled = true,
                minConfidence = 0.50f,
                outputFormat = YoloOutputFormat.ATTRIBUTES_FIRST
            )
        )

        assertEquals(1, detections.size)
        assertEquals(0.3f, detections.single().left, 0.001f)
        assertEquals(0.7f, detections.single().right, 0.001f)
    }

    @Test
    fun parse_overlappingBoxesKeepsHighestConfidenceDetection() {
        val output = arrayOf(
            arrayOf(
                floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.95f),
                floatArrayOf(0.52f, 0.5f, 0.4f, 0.4f, 0.70f),
                floatArrayOf(0.85f, 0.85f, 0.1f, 0.1f, 0.80f)
            )
        )

        val detections = YoloPersonDetectionParser.parse(
            rawOutput = output,
            config = PersonDetectorConfig(
                enabled = true,
                minConfidence = 0.50f,
                iouThreshold = 0.45f,
                outputFormat = YoloOutputFormat.BOXES_FIRST
            )
        )

        assertEquals(2, detections.size)
        assertTrue(detections.any { it.confidence == 0.95f })
        assertTrue(detections.none { it.confidence == 0.70f })
    }
}
