package com.example.chorevo.posedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTrackAssignerTest {

    /** A 33-landmark pose centred at (cx, cy). */
    private fun pose(cx: Float, cy: Float): List<Float> =
        (0 until 33).flatMap { i -> listOf(cx + i * 0.001f, cy + i * 0.001f, 0f, 1f) }

    private fun hand(marker: Float) = List(21 * 4) { marker }

    private fun track(id: String, vararg frames: Pair<Long, List<Float>>) =
        PoseTrack(trackId = id, frames = frames.map { (t, lm) -> PoseFrame(tMs = t, landmarks = lm) })

    @Test
    fun attachesHandsToTheDancerWithTheMatchingPose_evenWhenTrackerIdsDiffer() {
        val left = pose(0.25f, 0.5f)
        val right = pose(0.75f, 0.5f)
        // Template IDs: track_1 is the right dancer, track_2 the left one.
        val tracks = listOf(track("track_1", 0L to right), track("track_2", 0L to left))
        // Observations come in the opposite order (as ByteTrack might number them).
        val observations = listOf(
            HandObservation(0L, left, HandLandmarkFrame(0L, leftHand = hand(2f))),
            HandObservation(0L, right, HandLandmarkFrame(0L, leftHand = hand(1f)))
        )

        val handTracks = HandTrackAssigner.assign(tracks, observations).associateBy { it.trackId }

        assertEquals(1f, handTracks.getValue("track_1").frames.single().leftHand.first())
        assertEquals(2f, handTracks.getValue("track_2").frames.single().leftHand.first())
    }

    @Test
    fun ignoresObservationsThatDoNotMatchAnyDancerPose() {
        val tracks = listOf(track("track_1", 0L to pose(0.25f, 0.5f)))
        val observations = listOf(
            HandObservation(0L, pose(0.8f, 0.8f), HandLandmarkFrame(0L, rightHand = hand(1f)))
        )

        assertTrue(HandTrackAssigner.assign(tracks, observations).isEmpty())
    }

    @Test
    fun matchesOnlyFramesAtTheSameTimestamp_andSortsByTime() {
        val p = pose(0.5f, 0.5f)
        val tracks = listOf(track("track_1", 0L to p, 125L to p))
        val observations = listOf(
            HandObservation(125L, p, HandLandmarkFrame(125L, leftHand = hand(2f))),
            HandObservation(0L, p, HandLandmarkFrame(0L, leftHand = hand(1f))),
            HandObservation(250L, p, HandLandmarkFrame(250L, leftHand = hand(3f)))
        )

        val frames = HandTrackAssigner.assign(tracks, observations).single().frames

        assertEquals(listOf(0L, 125L), frames.map { it.tMs })
    }

    @Test
    fun eachObservationIsUsedOnce_closestDancerWins() {
        val p = pose(0.5f, 0.5f)
        val nearby = pose(0.52f, 0.5f)
        val tracks = listOf(track("track_1", 0L to nearby), track("track_2", 0L to p))
        val observations = listOf(HandObservation(0L, p, HandLandmarkFrame(0L, leftHand = hand(1f))))

        val handTracks = HandTrackAssigner.assign(tracks, observations)

        assertEquals(listOf("track_2"), handTracks.map { it.trackId })
    }

    @Test
    fun skipsObservationsWithNoHands() {
        val p = pose(0.5f, 0.5f)
        val tracks = listOf(track("track_1", 0L to p))
        val observations = listOf(HandObservation(0L, p, HandLandmarkFrame(0L)))

        assertTrue(HandTrackAssigner.assign(tracks, observations).isEmpty())
    }
}
