package com.example.chorevo.danceactivity

import com.example.chorevo.posedetection.HandLandmarkFrame
import com.example.chorevo.posedetection.HandTrack
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeStepsTest {

    private fun frames(count: Int, stepMs: Long = 1_000L): List<PoseFrame> =
        List(count) { PoseFrame(tMs = it * stepMs) }

    private fun template(count: Int, fps: Int = 1): PoseTemplate =
        PoseTemplate(fps = fps, frames = frames(count))

    // ── derivePracticeStepSegments ───────────────────────────────────────────

    @Test
    fun derive_nullTemplate_returnsEmpty() {
        assertTrue(derivePracticeStepSegments(null).isEmpty())
    }

    @Test
    fun derive_noFrames_returnsEmpty() {
        assertTrue(derivePracticeStepSegments(PoseTemplate(fps = 1)).isEmpty())
    }

    @Test
    fun derive_singleFrame_returnsEmpty() {
        assertTrue(derivePracticeStepSegments(template(1)).isEmpty())
    }

    @Test
    fun derive_twoFrames_isOneStepSpanningBothFrames() {
        val segments = derivePracticeStepSegments(template(2))

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].index)
        assertEquals(1, segments[0].total)
        assertEquals(0L, segments[0].startMs)
        assertEquals(1_000L, segments[0].endMs)
    }

    @Test
    fun derive_exactMultipleOfStepSize_makesEqualSteps() {
        // fps 1 -> 4 frames per step.
        val segments = derivePracticeStepSegments(template(8))

        assertEquals(2, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(3_000L, segments[0].endMs)
        assertEquals(4_000L, segments[1].startMs)
        assertEquals(7_000L, segments[1].endMs)
        assertTrue(segments.all { it.total == 2 })
        assertEquals(listOf(0, 1), segments.map { it.index })
    }

    @Test
    fun derive_trailingChunkAtMinimumSize_isKeptAsOwnStep() {
        // 10 frames -> 4, 4, 2. A last chunk of exactly the minimum (2) stays.
        val segments = derivePracticeStepSegments(template(10))

        assertEquals(3, segments.size)
        assertEquals(8_000L, segments[2].startMs)
        assertEquals(9_000L, segments[2].endMs)
    }

    @Test
    fun derive_shortTrailingChunk_isMergedIntoPreviousStep() {
        // 9 frames -> 4, 4, 1. The single trailing frame is below the minimum.
        val segments = derivePracticeStepSegments(template(9))

        assertEquals(2, segments.size)
        assertEquals(4_000L, segments[1].startMs)
        assertEquals(8_000L, segments[1].endMs)
        assertTrue(segments.all { it.total == 2 })
    }

    @Test
    fun derive_fewerFramesThanOneStep_isSingleStep() {
        val segments = derivePracticeStepSegments(template(3))

        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(2_000L, segments[0].endMs)
    }

    @Test
    fun derive_higherFps_usesLargerSteps() {
        // fps 2 -> 8 frames per step, minimum last step 4 frames.
        assertEquals(2, derivePracticeStepSegments(template(12, fps = 2)).size)
        // 11 frames -> 8 + 3, and 3 < 4, so the tail merges into one step.
        assertEquals(1, derivePracticeStepSegments(template(11, fps = 2)).size)
    }

    @Test
    fun derive_zeroFps_behavesLikeFpsOne() {
        val zero = derivePracticeStepSegments(template(10, fps = 0))
        val one = derivePracticeStepSegments(template(10, fps = 1))

        assertEquals(one, zero)
    }

    @Test
    fun derive_unsortedFrames_areSortedByTime() {
        val shuffled = PoseTemplate(
            fps = 1,
            frames = listOf(
                PoseFrame(tMs = 3_000L),
                PoseFrame(tMs = 0L),
                PoseFrame(tMs = 2_000L),
                PoseFrame(tMs = 1_000L)
            )
        )

        val segments = derivePracticeStepSegments(shuffled)

        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(3_000L, segments[0].endMs)
    }

    @Test
    fun derive_identicalTimestamps_giveStepOfAtLeastOneMillisecond() {
        val flat = PoseTemplate(fps = 1, frames = listOf(PoseFrame(tMs = 500L), PoseFrame(tMs = 500L)))

        val segments = derivePracticeStepSegments(flat)

        assertEquals(1, segments.size)
        assertEquals(500L, segments[0].startMs)
        assertEquals(501L, segments[0].endMs)
        assertEquals(1L, segments[0].durationMs)
    }

    // ── PracticeStepSegment ──────────────────────────────────────────────────

    @Test
    fun segment_title_isOneBased() {
        val segment = PracticeStepSegment(index = 1, total = 5, startMs = 0L, endMs = 10L)

        assertEquals("Step 2 of 5", segment.title)
    }

    @Test
    fun segment_duration_isEndMinusStart() {
        val segment = PracticeStepSegment(index = 0, total = 1, startMs = 1_000L, endMs = 4_500L)

        assertEquals(3_500L, segment.durationMs)
    }

    @Test
    fun segment_duration_neverBelowOne() {
        assertEquals(1L, PracticeStepSegment(0, 1, startMs = 100L, endMs = 100L).durationMs)
        assertEquals(1L, PracticeStepSegment(0, 1, startMs = 200L, endMs = 100L).durationMs)
    }

    // ── sliceTemplateForStep ─────────────────────────────────────────────────

    @Test
    fun slice_nullTemplate_returnsNull() {
        val segment = PracticeStepSegment(0, 1, 0L, 1_000L)

        assertNull(sliceTemplateForStep(null, segment))
    }

    @Test
    fun slice_keepsOnlyFramesInsideSegment_andRebasesTimes() {
        val segment = PracticeStepSegment(index = 1, total = 2, startMs = 4_000L, endMs = 7_000L)

        val sliced = sliceTemplateForStep(template(10), segment)

        assertNotNull(sliced)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), sliced!!.frames.map { it.tMs })
    }

    @Test
    fun slice_includesFramesOnBothSegmentBoundaries() {
        val segment = PracticeStepSegment(index = 0, total = 1, startMs = 1_000L, endMs = 2_000L)

        val sliced = sliceTemplateForStep(template(5), segment)

        assertEquals(listOf(0L, 1_000L), sliced!!.frames.map { it.tMs })
    }

    @Test
    fun slice_segmentWithNoFrames_returnsNull() {
        val segment = PracticeStepSegment(index = 0, total = 1, startMs = 50_000L, endMs = 60_000L)

        assertNull(sliceTemplateForStep(template(5), segment))
    }

    @Test
    fun slice_preservesLandmarksAndOtherTemplateFields() {
        val landmarks = listOf(0.1f, 0.2f, 0.3f, 1f)
        val source = PoseTemplate(
            fps = 7,
            frames = listOf(PoseFrame(tMs = 100L, landmarks = landmarks)),
            defaultTrackId = "t1"
        )

        val sliced = sliceTemplateForStep(source, PracticeStepSegment(0, 1, 100L, 200L))

        assertEquals(7, sliced!!.fps)
        assertEquals("t1", sliced.defaultTrackId)
        assertEquals(landmarks, sliced.frames.single().landmarks)
        assertEquals(0L, sliced.frames.single().tMs)
    }

    @Test
    fun slice_rebasesTracks_andDropsTracksWithNoFramesInRange() {
        val inRange = PoseTrack(trackId = "a", frames = frames(10))
        val outOfRange = PoseTrack(
            trackId = "b",
            frames = listOf(PoseFrame(tMs = 100_000L))
        )
        val source = PoseTemplate(fps = 1, frames = frames(10), tracks = listOf(inRange, outOfRange))
        val segment = PracticeStepSegment(index = 1, total = 2, startMs = 4_000L, endMs = 7_000L)

        val sliced = sliceTemplateForStep(source, segment)!!

        assertEquals(listOf("a"), sliced.tracks.map { it.trackId })
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), sliced.tracks.single().frames.map { it.tMs })
    }

    @Test
    fun slice_rebasesHandTracks_andDropsEmptyOnes() {
        val handFrames = List(10) { HandLandmarkFrame(tMs = it * 1_000L) }
        val source = PoseTemplate(
            fps = 1,
            frames = frames(10),
            handTracks = listOf(
                HandTrack(trackId = "a", frames = handFrames),
                HandTrack(trackId = "b", frames = listOf(HandLandmarkFrame(tMs = 99_000L)))
            )
        )
        val segment = PracticeStepSegment(index = 0, total = 3, startMs = 0L, endMs = 3_000L)

        val sliced = sliceTemplateForStep(source, segment)!!

        assertEquals(listOf("a"), sliced.handTracks.map { it.trackId })
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), sliced.handTracks.single().frames.map { it.tMs })
    }

    @Test
    fun deriveThenSlice_coversEveryFrameOfEachStep() {
        val source = template(9)
        val segments = derivePracticeStepSegments(source)

        val slicedFrameCounts = segments.map { sliceTemplateForStep(source, it)!!.frames.size }

        assertEquals(listOf(4, 5), slicedFrameCounts)
    }
}
