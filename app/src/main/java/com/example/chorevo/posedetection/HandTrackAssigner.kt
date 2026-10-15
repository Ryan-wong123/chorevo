package com.example.chorevo.posedetection

import kotlin.math.abs

/** A detected hand frame together with the body pose estimated from the same crop/frame. */
data class HandObservation(
    val tMs: Long,
    val pose: List<Float>,
    val hand: HandLandmarkFrame
)

/**
 * Attaches hand observations to the template's dancer tracks by matching the
 * body pose each hand was detected with, rather than by tracker ID.
 *
 * Template track IDs come from [MultiPersonPoseTracker] while upload crops are
 * keyed by ByteTrack IDs, so the two ID spaces do not line up. The pose
 * landmarks, however, are passed through unchanged, so at a given timestamp the
 * template frame closest to an observation's pose belongs to the same dancer.
 */
object HandTrackAssigner {

    /** Mean absolute x/y difference (normalized coordinates) still treated as the same dancer. */
    const val MAX_POSE_DISTANCE = 0.05f

    fun assign(
        tracks: List<PoseTrack>,
        observations: List<HandObservation>,
        maxPoseDistance: Float = MAX_POSE_DISTANCE
    ): List<HandTrack> {
        if (tracks.isEmpty() || observations.isEmpty()) return emptyList()

        val observationsByTime = observations
            .filter { it.hand.leftHand.isNotEmpty() || it.hand.rightHand.isNotEmpty() }
            .groupBy { it.tMs }
        val framesByTrack = linkedMapOf<String, MutableList<HandLandmarkFrame>>()

        val trackFramesByTime = tracks
            .flatMap { track -> track.frames.map { frame -> Triple(frame.tMs, track.trackId, frame.landmarks) } }
            .groupBy { it.first }

        for ((tMs, candidates) in observationsByTime) {
            val trackFrames = trackFramesByTime[tMs] ?: continue

            // Greedy one-to-one matching, closest pairs first.
            val pairs = trackFrames.flatMap { (_, trackId, landmarks) ->
                candidates.mapIndexedNotNull { index, observation ->
                    poseDistance(landmarks, observation.pose)
                        ?.takeIf { it <= maxPoseDistance }
                        ?.let { Triple(trackId, index, it) }
                }
            }.sortedBy { it.third }

            val usedTracks = mutableSetOf<String>()
            val usedObservations = mutableSetOf<Int>()
            for ((trackId, index, _) in pairs) {
                if (trackId in usedTracks || index in usedObservations) continue
                usedTracks += trackId
                usedObservations += index
                framesByTrack.getOrPut(trackId) { mutableListOf() } += candidates[index].hand.copy(tMs = tMs)
            }
        }

        return tracks.mapNotNull { track ->
            framesByTrack[track.trackId]
                ?.sortedBy { it.tMs }
                ?.takeIf { it.isNotEmpty() }
                ?.let { HandTrack(trackId = track.trackId, frames = it) }
        }
    }

    /** Mean absolute x/y distance between two flattened poses, or null if they cannot be compared. */
    internal fun poseDistance(a: List<Float>, b: List<Float>): Float? {
        val landmarkCount = minOf(a.size, b.size) / VALUES_PER_LANDMARK
        if (landmarkCount == 0) return null
        var total = 0f
        for (i in 0 until landmarkCount) {
            val base = i * VALUES_PER_LANDMARK
            total += abs(a[base] - b[base]) + abs(a[base + 1] - b[base + 1])
        }
        return total / (landmarkCount * 2)
    }

    private const val VALUES_PER_LANDMARK = 4
}
