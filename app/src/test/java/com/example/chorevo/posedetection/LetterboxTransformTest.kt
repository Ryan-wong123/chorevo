package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterboxTransformTest {

    @Test
    fun compute_landscapeFramePadsTopAndBottom() {
        val t = LetterboxTransform.compute(frameWidth = 1920, frameHeight = 1080, inputSize = 640)

        assertEquals(1f / 3f, t.scale, 0.0001f)
        assertEquals(0f, t.padX, 0.001f)
        assertEquals(140f, t.padY, 0.001f)
    }

    @Test
    fun toFrameSpace_mapsBoxInsideLetterboxBackToFrameCoordinates() {
        val t = LetterboxTransform.compute(frameWidth = 1920, frameHeight = 1080, inputSize = 640)
        // Box covering the right half of the frame, full frame height, in model-input space.
        val box = DetectionBox.normalized(
            left = 0.5f, top = 140f / 640f, right = 1f, bottom = 500f / 640f, confidence = 0.9f
        )

        val mapped = t.toFrameSpace(box)!!

        assertEquals(0.5f, mapped.left, 0.001f)
        assertEquals(1f, mapped.right, 0.001f)
        assertEquals(0f, mapped.top, 0.001f)
        assertEquals(1f, mapped.bottom, 0.001f)
    }

    @Test
    fun toFrameSpace_portraitFramePadsLeftAndRight() {
        val t = LetterboxTransform.compute(frameWidth = 1080, frameHeight = 1920, inputSize = 640)
        val box = DetectionBox.normalized(left = 0.5f, top = 0f, right = 0.75f, bottom = 1f, confidence = 0.9f)

        val mapped = t.toFrameSpace(box)!!

        // Centre of the input is the centre of the frame.
        assertEquals(0.5f, mapped.left, 0.001f)
        assertEquals(0f, mapped.top, 0.001f)
        assertEquals(1f, mapped.bottom, 0.001f)
    }

    @Test
    fun toFrameSpace_boxEntirelyInPaddingIsDropped() {
        val t = LetterboxTransform.compute(frameWidth = 1920, frameHeight = 1080, inputSize = 640)
        val box = DetectionBox.normalized(left = 0.1f, top = 0f, right = 0.9f, bottom = 0.1f, confidence = 0.9f)

        assertNull(t.toFrameSpace(box))
    }
}
