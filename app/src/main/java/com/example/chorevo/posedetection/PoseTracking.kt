package com.example.chorevo.posedetection

import kotlin.math.hypot
import kotlin.math.max

private data class PoseSample(
    val tMs: Long,
    val landmarks: List<Float>,
    val bounds: PoseBounds,
    val avgVisibility: Float
)

private data class TrackBuilder(
    val ordinal: Int,
    val frames: MutableList<PoseFrame> = mutableListOf(),
    var visibilitySum: Float = 0f,
    var sampleCount: Int = 0,
    var lastBounds: PoseBounds? = null,
    var missedSteps: Int = 0
) {
    val trackId: String
        get() = "track_$ordinal"

    fun add(sample: PoseSample) {
        frames += PoseFrame(tMs = sample.tMs, landmarks = sample.landmarks)
        visibilitySum += sample.avgVisibility
        sampleCount++
        lastBounds = sample.bounds
        missedSteps = 0
    }

    fun toPoseTrack(label: String): PoseTrack {
        val avgVisibility = if (sampleCount == 0) 0f else visibilitySum / sampleCount.toFloat()
        return PoseTrack(
            trackId = trackId,
            label = label,
            frames = frames.toList(),
            avgVisibility = avgVisibility
        )
    }
}

/** Tracks multiple pose detections across sampled video frames and creates a multi-track template. */
class MultiPersonPoseTracker(
    private val fps: Int,
    private val minTrackFrames: Int = 2,
    private val maxMissedSteps: Int = 3
) {
    private val trackBuilders = mutableListOf<TrackBuilder>()
    private val frameSizesByTime = linkedMapOf<Long, Pair<Int, Int>>()
    private var nextTrackOrdinal = 1

    fun addFrame(
        tMs: Long,
        width: Int,
        height: Int,
        detections: List<List<Float>>
    ) {
        if (width > 0 && height > 0) {
            frameSizesByTime[tMs] = width to height
        }

        val samples = detections.mapNotNull { landmarks ->
            val bounds = landmarks.poseBounds() ?: return@mapNotNull null
            PoseSample(
                tMs = tMs,
                landmarks = landmarks,
                bounds = bounds,
                avgVisibility = landmarks.averageVisibility()
            )
        }.sortedByDescending { it.bounds.area * max(it.avgVisibility, 0.1f) }

        val assignedTrackIds = mutableSetOf<String>()

        for (sample in samples) {
            val bestTrack = trackBuilders
                .filter { it.trackId !in assignedTrackIds }
                .mapNotNull { track ->
                    val lastBounds = track.lastBounds ?: return@mapNotNull null
                    if (track.missedSteps > maxMissedSteps) return@mapNotNull null
                    val cost = trackMatchCost(lastBounds, sample.bounds, track.missedSteps)
                    if (cost > 0.55f) return@mapNotNull null
                    track to cost
                }
                .minByOrNull { it.second }
                ?.first

            val target = bestTrack ?: TrackBuilder(ordinal = nextTrackOrdinal++).also {
                trackBuilders += it
            }

            target.add(sample)
            assignedTrackIds += target.trackId
        }

        for (track in trackBuilders) {
            if (track.trackId !in assignedTrackIds) {
                track.missedSteps++
            }
        }
    }

    fun buildTemplate(): PoseTemplate? {
        val tracks = trackBuilders
            .filter { it.frames.size >= minTrackFrames }
            .sortedWith(
                compareByDescending<TrackBuilder> { it.frames.size }
                    .thenByDescending { if (it.sampleCount == 0) 0f else it.visibilitySum / it.sampleCount.toFloat() }
            )
            .mapIndexed { index, track -> track.toPoseTrack(label = "Dancer ${index + 1}") }

        if (tracks.isEmpty()) return null

        val defaultTrack = tracks.first()
        return PoseTemplate(
            fps = fps,
            frames = defaultTrack.frames,
            tracks = tracks,
            defaultTrackId = defaultTrack.trackId,
            selectionPreview = buildSelectionPreview(tracks)
        )
    }

    private fun buildSelectionPreview(tracks: List<PoseTrack>): PoseSelectionPreview? {
        val candidatesByTime = linkedMapOf<Long, MutableList<PoseSelectionCandidate>>()

        for (track in tracks) {
            for (frame in track.frames) {
                val bounds = frame.landmarks.poseBounds() ?: continue
                candidatesByTime
                    .getOrPut(frame.tMs) { mutableListOf() }
                    .add(
                        PoseSelectionCandidate(
                            trackId = track.trackId,
                            landmarks = frame.landmarks,
                            bounds = bounds
                        )
                    )
            }
        }

        val bestPreview = candidatesByTime.maxWithOrNull(
            compareBy<Map.Entry<Long, MutableList<PoseSelectionCandidate>>> { it.value.size }
                .thenBy {
                    it.value.sumOf { candidate ->
                        candidate.landmarks.averageVisibility().toDouble()
                    }
                }
        ) ?: return null

        val frameSize = frameSizesByTime[bestPreview.key]
        return PoseSelectionPreview(
            timeMs = bestPreview.key,
            frameWidth = frameSize?.first ?: 0,
            frameHeight = frameSize?.second ?: 0,
            candidates = bestPreview.value.sortedBy { candidate ->
                tracks.indexOfFirst { it.trackId == candidate.trackId }
            }
        )
    }

    private fun trackMatchCost(previous: PoseBounds, current: PoseBounds, missedSteps: Int): Float {
        val centerDistance = hypot(
            (previous.centerX - current.centerX).toDouble(),
            (previous.centerY - current.centerY).toDouble()
        ).toFloat()
        val iouPenalty = 1f - previous.iou(current)
        val areaPenalty = kotlin.math.abs(previous.area - current.area)
        return centerDistance + (iouPenalty * 0.2f) + (areaPenalty * 0.35f) + (missedSteps * 0.08f)
    }
}
