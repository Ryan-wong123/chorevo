package com.example.chorevo.posedetection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private data class PoseSample(
    val tMs: Long,
    val landmarks: List<Float>,
    val bounds: PoseBounds,
    val avgVisibility: Float,
    val rawArea: Float
)

private data class TrackBuilder(
    val ordinal: Int,
    val frames: MutableList<PoseFrame> = mutableListOf(),
    val boundsByTime: MutableMap<Long, PoseBounds> = linkedMapOf(),
    var areaSum: Float = 0f,
    var visibilitySum: Float = 0f,
    var sampleCount: Int = 0,
    var lastBounds: PoseBounds? = null,
    var missedSteps: Int = 0
) {
    val trackId: String
        get() = "track_$ordinal"

    fun add(sample: PoseSample) {
        frames += PoseFrame(tMs = sample.tMs, landmarks = sample.landmarks)
        boundsByTime[sample.tMs] = sample.bounds
        areaSum += sample.rawArea
        visibilitySum += sample.avgVisibility
        sampleCount++
        lastBounds = sample.bounds
        missedSteps = 0
    }

    val averageVisibility: Float
        get() = if (sampleCount == 0) 0f else visibilitySum / sampleCount.toFloat()

    val averageArea: Float
        get() = if (sampleCount == 0) 0f else areaSum / sampleCount.toFloat()

    val firstTimeMs: Long
        get() = frames.minOfOrNull { it.tMs } ?: 0L

    val lastTimeMs: Long
        get() = frames.maxOfOrNull { it.tMs } ?: 0L

    val representativeBounds: PoseBounds?
        get() {
            if (boundsByTime.isEmpty()) return null
            val values = boundsByTime.values
            return PoseBounds(
                left = values.sumOf { it.left.toDouble() }.toFloat() / values.size.toFloat(),
                top = values.sumOf { it.top.toDouble() }.toFloat() / values.size.toFloat(),
                right = values.sumOf { it.right.toDouble() }.toFloat() / values.size.toFloat(),
                bottom = values.sumOf { it.bottom.toDouble() }.toFloat() / values.size.toFloat()
            )
        }

    fun toPoseTrack(label: String): PoseTrack {
        return PoseTrack(
            trackId = trackId,
            label = label,
            frames = frames.sortedBy { it.tMs },
            avgVisibility = averageVisibility
        )
    }

    fun mergeFrom(other: TrackBuilder) {
        val mergedFrames = (frames + other.frames)
            .associateBy { it.tMs }
            .values
            .sortedBy { it.tMs }

        frames.clear()
        frames.addAll(mergedFrames)

        boundsByTime.putAll(other.boundsByTime)
        areaSum += other.areaSum
        visibilitySum += other.visibilitySum
        sampleCount += other.sampleCount
        lastBounds = frames.lastOrNull()?.landmarks?.poseBounds() ?: lastBounds
        missedSteps = minOf(missedSteps, other.missedSteps)
    }
}

/** Tracks multiple pose detections across sampled video frames and creates a multi-track template. */
class MultiPersonPoseTracker(
    private val fps: Int,
    private val minTrackFrames: Int = 3,
    private val maxMissedSteps: Int = 3
) {
    companion object {
        private const val MIN_AVERAGE_VISIBILITY = 0.35f
        private const val MIN_AVERAGE_AREA = 0.015f
        private const val MIN_TRACK_COVERAGE = 0.35f
        private const val DUPLICATE_OVERLAP_IOU = 0.65f
        private const val DUPLICATE_PREVIEW_IOU = 0.60f
        private const val DUPLICATE_CENTER_DISTANCE = 0.10f
    }

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
            val rawBounds = landmarks.poseBounds(padding = 0f) ?: bounds
            PoseSample(
                tMs = tMs,
                landmarks = landmarks,
                bounds = bounds,
                avgVisibility = landmarks.averageVisibility(),
                rawArea = rawBounds.area
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
        val mergedTrackBuilders = mergeDuplicateTracks(
            trackBuilders.filter(::isStableTrack)
        )

        val labeledTracks = mergedTrackBuilders
            .sortedWith(
                compareByDescending<TrackBuilder> { it.frames.size }
                    .thenByDescending { it.averageVisibility }
            )
            .mapIndexed { index, track -> track.toPoseTrack(label = "Dancer ${index + 1}") }

        if (labeledTracks.isEmpty()) return null

        val preview = buildSelectionPreview(labeledTracks)
        val previewTrackIds = preview?.candidates
            ?.map { it.trackId }
            ?.toSet()
            .orEmpty()
        val tracks = if (previewTrackIds.size >= 2) {
            labeledTracks.filter { it.trackId in previewTrackIds }
        } else {
            labeledTracks
        }

        if (tracks.isEmpty()) return null

        val defaultTrack = tracks.first()
        return PoseTemplate(
            fps = fps,
            frames = defaultTrack.frames,
            tracks = tracks,
            defaultTrackId = defaultTrack.trackId,
            selectionPreview = preview?.let { selectedPreview ->
                if (previewTrackIds.size >= 2) {
                    selectedPreview.copy(
                        candidates = selectedPreview.candidates.filter { it.trackId in previewTrackIds }
                    )
                } else {
                    selectedPreview
                }
            }
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

        val dedupedCandidatesByTime = candidatesByTime
            .mapValues { (_, candidates) -> distinctPreviewCandidates(candidates) }
            .filterValues { it.isNotEmpty() }

        val bestPreview = dedupedCandidatesByTime.maxWithOrNull(
            compareBy<Map.Entry<Long, List<PoseSelectionCandidate>>> { it.value.size }
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

    private fun isStableTrack(track: TrackBuilder): Boolean {
        if (track.frames.size < minTrackFrames) return false
        if (track.averageVisibility < MIN_AVERAGE_VISIBILITY) return false
        if (track.averageArea < MIN_AVERAGE_AREA) return false

        val frameStepMs = frameStepMs()
        val expectedFrames = max(1, (((track.lastTimeMs - track.firstTimeMs) / frameStepMs) + 1).toInt())
        val coverage = track.frames.size.toFloat() / expectedFrames.toFloat()
        return coverage >= MIN_TRACK_COVERAGE
    }

    private fun mergeDuplicateTracks(tracks: List<TrackBuilder>): List<TrackBuilder> {
        val merged = mutableListOf<TrackBuilder>()

        for (track in tracks.sortedWith(
            compareByDescending<TrackBuilder> { it.frames.size }
                .thenByDescending { it.averageVisibility }
                .thenByDescending { it.averageArea }
        )) {
            val existing = merged.firstOrNull { shouldMergeTracks(it, track) }
            if (existing != null) {
                existing.mergeFrom(track)
            } else {
                merged += track
            }
        }

        return merged
    }

    private fun shouldMergeTracks(primary: TrackBuilder, candidate: TrackBuilder): Boolean {
        val sharedTimes = primary.boundsByTime.keys.intersect(candidate.boundsByTime.keys)
        if (sharedTimes.isNotEmpty()) {
            val averageIou = sharedTimes
                .map { time -> primary.boundsByTime.getValue(time).iou(candidate.boundsByTime.getValue(time)) }
                .average()
                .toFloat()
            val averageCenterDistance = sharedTimes
                .map { time ->
                    poseCenterDistance(
                        primary.boundsByTime.getValue(time),
                        candidate.boundsByTime.getValue(time)
                    ).toDouble()
                }
                .average()
                .toFloat()

            return averageIou >= DUPLICATE_OVERLAP_IOU &&
                averageCenterDistance <= DUPLICATE_CENTER_DISTANCE
        }

        val primaryBounds = primary.representativeBounds ?: return false
        val candidateBounds = candidate.representativeBounds ?: return false
        val gapMs = minOf(
            abs(primary.firstTimeMs - candidate.lastTimeMs),
            abs(candidate.firstTimeMs - primary.lastTimeMs)
        )

        return gapMs <= frameStepMs() * (maxMissedSteps + 2) &&
            primaryBounds.iou(candidateBounds) >= 0.45f &&
            poseCenterDistance(primaryBounds, candidateBounds) <= 0.12f &&
            abs(primary.averageArea - candidate.averageArea) <= 0.12f
    }

    private fun distinctPreviewCandidates(
        candidates: List<PoseSelectionCandidate>
    ): List<PoseSelectionCandidate> {
        val kept = mutableListOf<PoseSelectionCandidate>()

        for (candidate in candidates.sortedByDescending { previewCandidatePriority(it) }) {
            val overlapsExisting = kept.any { existing ->
                existing.trackId != candidate.trackId &&
                    existing.bounds.iou(candidate.bounds) >= DUPLICATE_PREVIEW_IOU &&
                    poseCenterDistance(existing.bounds, candidate.bounds) <= DUPLICATE_CENTER_DISTANCE
            }

            if (!overlapsExisting) {
                kept += candidate
            }
        }

        return kept
    }

    private fun previewCandidatePriority(candidate: PoseSelectionCandidate): Float {
        return candidate.bounds.area * max(candidate.landmarks.averageVisibility(), 0.1f)
    }

    private fun poseCenterDistance(first: PoseBounds, second: PoseBounds): Float {
        return hypot(
            (first.centerX - second.centerX).toDouble(),
            (first.centerY - second.centerY).toDouble()
        ).toFloat()
    }

    private fun frameStepMs(): Long = (1000L / max(1, fps)).coerceAtLeast(1L)

    private fun trackMatchCost(previous: PoseBounds, current: PoseBounds, missedSteps: Int): Float {
        val centerDistance = poseCenterDistance(previous, current)
        val iouPenalty = 1f - previous.iou(current)
        val areaPenalty = abs(previous.area - current.area)
        return centerDistance + (iouPenalty * 0.2f) + (areaPenalty * 0.35f) + (missedSteps * 0.08f)
    }
}
