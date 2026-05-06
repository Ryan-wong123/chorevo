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
    private val maxMissedSteps: Int = 3,
    private val minAverageVisibility: Float = 0.35f,
    private val minAverageArea: Float = 0.006f,
    private val minTrackCoverage: Float = 0.35f
) {
    companion object {
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
            val bestTrack = findBestActiveTrack(sample, assignedTrackIds)
                ?: findBestRecentlyLostTrack(sample, assignedTrackIds)

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
            mergeFragmentedTracks(
                mergeDuplicateTracks(trackBuilders)
            )
        ).filter(::isStableTrack)

        val labeledTracks = mergedTrackBuilders
            .sortedWith(
                compareByDescending<TrackBuilder> { it.frames.size }
                    .thenByDescending { it.averageVisibility }
            )
            .mapIndexed { index, track -> track.toPoseTrack(label = "Dancer ${index + 1}") }

        if (labeledTracks.isEmpty()) return null

        val preview = buildSelectionPreview(labeledTracks)
        val tracks = labeledTracks

        val defaultTrack = tracks.first()
        return PoseTemplate(
            fps = fps,
            frames = defaultTrack.frames,
            tracks = tracks,
            defaultTrackId = defaultTrack.trackId,
            selectionPreview = preview
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
        if (track.averageVisibility < minAverageVisibility) return false
        if (track.averageArea < minAverageArea) return false

        val frameStepMs = frameStepMs()
        val expectedFrames = max(1, (((track.lastTimeMs - track.firstTimeMs) / frameStepMs) + 1).toInt())
        val coverage = track.frames.size.toFloat() / expectedFrames.toFloat()
        return coverage >= minTrackCoverage
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

    private fun mergeFragmentedTracks(tracks: List<TrackBuilder>): List<TrackBuilder> {
        val merged = mutableListOf<TrackBuilder>()

        for (track in tracks.sortedBy { it.firstTimeMs }) {
            val existing = merged.firstOrNull { shouldMergeFragments(it, track) }
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

    private fun shouldMergeFragments(primary: TrackBuilder, candidate: TrackBuilder): Boolean {
        if (primary.trackId == candidate.trackId) return false

        val overlapStart = maxOf(primary.firstTimeMs, candidate.firstTimeMs)
        val overlapEnd = minOf(primary.lastTimeMs, candidate.lastTimeMs)
        val overlapMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
        val shorterDurationMs = minOf(
            (primary.lastTimeMs - primary.firstTimeMs).coerceAtLeast(frameStepMs()),
            (candidate.lastTimeMs - candidate.firstTimeMs).coerceAtLeast(frameStepMs())
        )
        val overlapRatio = overlapMs.toFloat() / shorterDurationMs.toFloat()
        if (overlapRatio > 0.20f) return false

        val gapMs = when {
            primary.lastTimeMs <= candidate.firstTimeMs -> candidate.firstTimeMs - primary.lastTimeMs
            candidate.lastTimeMs <= primary.firstTimeMs -> primary.firstTimeMs - candidate.lastTimeMs
            else -> 0L
        }
        if (gapMs > frameStepMs() * maxMissedSteps) return false

        val primaryTransitionBounds = primary.boundsNearestTo(candidate.firstTimeMs) ?: return false
        val candidateTransitionBounds = candidate.boundsNearestTo(primary.lastTimeMs) ?: return false
        val centerDistance = poseCenterDistance(primaryTransitionBounds, candidateTransitionBounds)
        val sizeRatio = poseSizeRatio(primaryTransitionBounds, candidateTransitionBounds)

        return centerDistance < 0.18f && sizeRatio in 0.5f..2.0f
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

    private fun poseSizeRatio(first: PoseBounds, second: PoseBounds): Float {
        val minArea = minOf(first.area, second.area).coerceAtLeast(1e-4f)
        val maxArea = maxOf(first.area, second.area)
        return maxArea / minArea
    }

    private fun TrackBuilder.boundsNearestTo(tMs: Long): PoseBounds? {
        return boundsByTime.minByOrNull { abs(it.key - tMs) }?.value
            ?: representativeBounds
    }

    private fun frameStepMs(): Long = (1000L / max(1, fps)).coerceAtLeast(1L)

    private fun isPlausibleTrackMatch(
        previous: PoseBounds,
        current: PoseBounds,
        missedSteps: Int
    ): Boolean {
        val centerDistance = poseCenterDistance(previous, current)
        val iou = previous.iou(current)
        val minArea = minOf(previous.area, current.area).coerceAtLeast(1e-4f)
        val maxArea = maxOf(previous.area, current.area)
        val areaRatio = maxArea / minArea

        if (iou < 0.05f && centerDistance > 0.12f) return false
        if (areaRatio > 2.8f && centerDistance > 0.06f) return false
        if (missedSteps == 0 && areaRatio > 2.2f && centerDistance > 0.03f) return false

        return true
    }

    private fun trackMatchCost(previous: PoseBounds, current: PoseBounds, missedSteps: Int): Float {
        val centerDistance = poseCenterDistance(previous, current)
        val iouPenalty = 1f - previous.iou(current)
        val areaPenalty = abs(previous.area - current.area)
        return centerDistance + (iouPenalty * 0.2f) + (areaPenalty * 0.35f) + (missedSteps * 0.08f)
    }

    private fun findBestActiveTrack(
        sample: PoseSample,
        assignedTrackIds: Set<String>
    ): TrackBuilder? {
        return trackBuilders
            .filter { it.trackId !in assignedTrackIds && it.missedSteps == 0 }
            .mapNotNull { track ->
                val lastBounds = track.lastBounds ?: return@mapNotNull null
                if (!isPlausibleTrackMatch(lastBounds, sample.bounds, track.missedSteps)) {
                    return@mapNotNull null
                }
                val cost = trackMatchCost(lastBounds, sample.bounds, track.missedSteps)
                if (cost > 0.55f) return@mapNotNull null
                track to cost
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun findBestRecentlyLostTrack(
        sample: PoseSample,
        assignedTrackIds: Set<String>
    ): TrackBuilder? {
        return trackBuilders
            .filter { track ->
                track.trackId !in assignedTrackIds &&
                    track.missedSteps in 1..maxMissedSteps
            }
            .mapNotNull { track ->
                val lastBounds = track.lastBounds ?: return@mapNotNull null
                if (!isPlausibleRecentlyLostTrackMatch(lastBounds, sample.bounds)) {
                    return@mapNotNull null
                }
                val cost = recentlyLostTrackMatchCost(lastBounds, sample.bounds, track.missedSteps)
                if (cost > 0.75f) return@mapNotNull null
                track to cost
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun isPlausibleRecentlyLostTrackMatch(
        previous: PoseBounds,
        current: PoseBounds
    ): Boolean {
        return poseCenterDistance(previous, current) < 0.20f &&
            poseSizeRatio(previous, current) in 0.45f..2.2f
    }

    private fun recentlyLostTrackMatchCost(
        previous: PoseBounds,
        current: PoseBounds,
        missedSteps: Int
    ): Float {
        val centerDistance = poseCenterDistance(previous, current)
        val iouPenalty = 1f - previous.iou(current)
        val areaPenalty = abs(previous.area - current.area)
        return centerDistance + (iouPenalty * 0.1f) + (areaPenalty * 0.35f) + (missedSteps * 0.01f)
    }
}
