package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HolisticFrameSelectorTest {

    @Test
    fun primaryPose_ignoresFramesWithoutPose() {
        val frame = HolisticFrameLandmarks(
            pose = listOf(
                0.2f, 0.2f, 0f, 0.9f,
                0.8f, 0.8f, 0f, 0.9f
            ),
            leftHand = List(21 * 4) { 0.25f },
            rightHand = emptyList()
        )

        assertEquals(
            frame,
            HolisticFrameSelector.primaryPose(
                listOf(
                    HolisticFrameLandmarks(),
                    frame
                )
            )
        )
    }

    @Test
    fun primaryPose_returnsNullWhenOnlyHandsArePresent() {
        val handOnlyFrame = HolisticFrameLandmarks(
            leftHand = List(21 * 4) { 0.5f },
            rightHand = List(21 * 4) { 0.6f }
        )

        assertNull(HolisticFrameSelector.primaryPose(listOf(handOnlyFrame)))
    }
}
