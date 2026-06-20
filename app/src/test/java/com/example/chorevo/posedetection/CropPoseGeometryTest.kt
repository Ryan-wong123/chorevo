package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Test

class CropPoseGeometryTest {

    @Test
    fun expandBox_addsPaddingAndClampsToFrameBounds() {
        val box = DetectionBox.normalized(
            left = 0.05f,
            top = 0.10f,
            right = 0.25f,
            bottom = 0.60f,
            confidence = 0.80f,
            source = DetectionSource.Yolo
        )

        val expanded = CropPoseGeometry.expandBox(box, paddingFraction = 0.50f)

        assertEquals(0.00f, expanded.left, EPSILON)
        assertEquals(0.00f, expanded.top, EPSILON)
        assertEquals(0.35f, expanded.right, EPSILON)
        assertEquals(0.85f, expanded.bottom, EPSILON)
    }

    @Test
    fun toPixelCropRect_convertsNormalizedBoxToBoundedPixelRect() {
        val box = DetectionBox.normalized(
            left = 0.10f,
            top = 0.20f,
            right = 0.40f,
            bottom = 0.75f,
            confidence = 0.70f
        )

        val cropRect = CropPoseGeometry.toPixelCropRect(
            box = box,
            frameWidth = 200,
            frameHeight = 100
        )

        assertEquals(20, cropRect.left)
        assertEquals(20, cropRect.top)
        assertEquals(60, cropRect.width)
        assertEquals(55, cropRect.height)
    }

    @Test
    fun remapCropLandmarksToFullFrame_mapsNormalizedCropCoordinates() {
        val cropBox = DetectionBox.normalized(
            left = 0.25f,
            top = 0.10f,
            right = 0.75f,
            bottom = 0.90f,
            confidence = 0.90f
        )
        val cropLandmarks = listOf(
            0.00f, 0.00f, -0.10f, 0.20f,
            0.50f, 0.50f, 0.30f, 0.80f,
            1.00f, 1.00f, 0.50f, 1.00f
        )

        val remapped = CropPoseGeometry.remapCropLandmarksToFullFrame(
            landmarks = cropLandmarks,
            cropBox = cropBox
        )

        assertEquals(0.25f, remapped[0], EPSILON)
        assertEquals(0.10f, remapped[1], EPSILON)
        assertEquals(-0.10f, remapped[2], EPSILON)
        assertEquals(0.20f, remapped[3], EPSILON)

        assertEquals(0.50f, remapped[4], EPSILON)
        assertEquals(0.50f, remapped[5], EPSILON)
        assertEquals(0.30f, remapped[6], EPSILON)
        assertEquals(0.80f, remapped[7], EPSILON)

        assertEquals(0.75f, remapped[8], EPSILON)
        assertEquals(0.90f, remapped[9], EPSILON)
        assertEquals(0.50f, remapped[10], EPSILON)
        assertEquals(1.00f, remapped[11], EPSILON)
    }

    @Test
    fun remapHolisticLandmarksToFullFrame_mapsPoseAndHandsWithoutFaceFields() {
        val cropBox = DetectionBox.normalized(
            left = 0.20f,
            top = 0.30f,
            right = 0.70f,
            bottom = 0.90f,
            confidence = 0.90f
        )
        val pose = listOf(0.50f, 0.50f, -0.10f, 0.80f)
        val leftHand = listOf(0.00f, 1.00f, 0.20f, 0.70f)
        val rightHand = listOf(1.00f, 0.00f, 0.30f, 0.60f)

        val remapped = CropPoseGeometry.remapHolisticLandmarksToFullFrame(
            landmarks = HolisticFrameLandmarks(
                pose = pose,
                leftHand = leftHand,
                rightHand = rightHand
            ),
            cropBox = cropBox
        )

        assertEquals(0.45f, remapped.pose[0], EPSILON)
        assertEquals(0.60f, remapped.pose[1], EPSILON)
        assertEquals(-0.10f, remapped.pose[2], EPSILON)
        assertEquals(0.80f, remapped.pose[3], EPSILON)

        assertEquals(0.20f, remapped.leftHand[0], EPSILON)
        assertEquals(0.90f, remapped.leftHand[1], EPSILON)
        assertEquals(0.20f, remapped.leftHand[2], EPSILON)
        assertEquals(0.70f, remapped.leftHand[3], EPSILON)

        assertEquals(0.70f, remapped.rightHand[0], EPSILON)
        assertEquals(0.30f, remapped.rightHand[1], EPSILON)
        assertEquals(0.30f, remapped.rightHand[2], EPSILON)
        assertEquals(0.60f, remapped.rightHand[3], EPSILON)
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
