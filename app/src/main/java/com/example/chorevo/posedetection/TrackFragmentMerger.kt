package com.example.chorevo.posedetection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class TrackFragmentMergerConfig(
    val maxGapFrames: Int = 24,
    val maxGapDurationMs: Long = 3_000L,
    val maxCenterDistance: Float = 0.18f,
    val maxCenterDriftPerGapFrame: Float = 0.015f,
    val minSizeRatio: Float = 0.45f,
    val maxSizeRatio: Float = 2.20f,
    val maxOverlapRatio: Float = 0.20f,
    val duplicateOverlapIou: Float = 0.70f,
    val duplicateCenterDistance: Float = 0.08f,
    val minMovementDirectionCosine: Float = -0.20f
)

data class TrackFragmentMergeResult(
    val tracks: List<PoseTrack>,
    val replacedTrackIds: Map<String, String> = emptyMap(),
    val mergedTrackCount: Int = 0
)

class TrackFragmentMerger(
    private val fps: Int,
    private val config: TrackFragmentMergerConfig = TrackFragmentMergerConfig()
) {
    private val frameStepMs = (1000L / max(1, fps)).coerceAtLeast(1L)

    fun merge(tracks: List<PoseTrack>): TrackFragmentMergeResult {
        if (tracks.size < 2) {
            return TrackFragmentMergeResult(tracks = tracks)
        }

        val working = tracks
            .filter { it.frames.isNotEmpty() }
            .sortedWith(compareBy<PoseTrack> { it.firstTimeMs() }.thenBy { it.trackId })
            .toMutableList()
        val replacements = linkedMapOf<String, String>()
        var mergeCount = 0

        var changed: Boolean
        do {
            changed = false
            mergeLoop@ for (primaryIndex in working.indices) {
                for (candidateIndex in working.indices) {
                    if (primaryIndex == candidateIndex) continue
                    val primary = working[primaryIndex]
                    val candidate = working[candidateIndex]
                    if (!shouldMerge(primary, candidate)) continue

                    val target = choosePrimaryTrack(primary, candidate)
                    val source = if (target.trackId == primary.trackId) candidate else primary
                    val merged = mergeTracks(target, source)
                    val targetIndex = if (target.trackId == primary.trackId) primaryIndex else candidateIndex
                    val sourceIndex = if (source.trackId == primary.trackId) primaryIndex else candidateIndex

                    working[targetIndex] = merged
                    working.removeAt(sourceIndex)
                    replacements[source.trackId] = target.trackId
                    mergeCount++
                    changed = true
                    break@mergeLoop
                }
            }
        } while (changed)

        return TrackFragmentMergeResult(
            tracks = working.sortedWith(
                compareByDescending<PoseTrack> { it.frames.size }
                    .thenByDescending { it.avgVisibility }
                    .thenBy { it.trackId }
            ),
            replacedTrackIds = replacements,
            mergedTrackCount = mergeCount
        )
    }

    private fun shouldMerge(first: PoseTrack, second: PoseTrack): Boolean {
        val duplicate = isClearDuplicate(first, second)
        val overlapRatio = overlapRatio(first, second)
        if (overlapRatio > config.maxOverlapRatio && !duplicate) return false
        if (duplicate) return true

        val ordered = orderByTime(first, second)
        val gapMs = ordered.second.firstTimeMs() - ordered.first.lastTimeMs()
        if (gapMs < 0L) return false
        if (gapMs > config.maxGapDurationMs) return false

        val gapFrames = (gapMs / frameStepMs).toInt().coerceAtLeast(0)
        if (gapFrames > config.maxGapFrames) return false

        val endBounds = ordered.first.lastReliableBounds() ?: return false
        val startBounds = ordered.second.firstReliableBounds() ?: return false
        val centerDistance = centerDistance(endBounds, startBounds)
        val allowedCenterDistance = config.maxCenterDistance +
            (gapFrames * config.maxCenterDriftPerGapFrame)
        if (centerDistance > allowedCenterDistance) return false

        val sizeRatio = sizeRatio(endBounds, startBounds)
        if (sizeRatio !in config.minSizeRatio..config.maxSizeRatio) return false

        return movementDirectionIsPlausible(ordered.first, ordered.second, endBounds, startBounds)
    }

    private fun isClearDuplicate(first: PoseTrack, second: PoseTrack): Boolean {
        val firstByTime = first.frames.associateBy { it.tMs }
        val sharedFrames = second.frames.mapNotNull { secondFrame ->
            firstByTime[secondFrame.tMs]?.let { firstFrame -> firstFrame to secondFrame }
        }
        if (sharedFrames.isEmpty()) return false

        val averageIou = sharedFrames
            .mapNotNull { (firstFrame, secondFrame) ->
                val firstBounds = firstFrame.landmarks.poseBounds() ?: return@mapNotNull null
                val secondBounds = secondFrame.landmarks.poseBounds() ?: return@mapNotNull null
                firstBounds.iou(secondBounds)
            }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: return false

        val averageCenterDistance = sharedFrames
            .mapNotNull { (firstFrame, secondFrame) ->
                val firstBounds = firstFrame.landmarks.poseBounds() ?: return@mapNotNull null
                val secondBounds = secondFrame.landmarks.poseBounds() ?: return@mapNotNull null
                centerDistance(firstBounds, secondBounds)
            }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: return false

        return averageIou >= config.duplicateOverlapIou &&
            averageCenterDistance <= config.duplicateCenterDistance
    }

    private fun movementDirectionIsPlausible(
        first: PoseTrack,
        second: PoseTrack,
        endBounds: PoseBounds,
        startBounds: PoseBounds
    ): Boolean {
        val transition = Vector(
            x = startBounds.centerX - endBounds.centerX,
            y = startBounds.centerY - endBounds.centerY
        )
        if (transition.magnitude < 0.02f) return true

        val firstVector = first.movementVector()
        val secondVector = second.movementVector()
        val knownDirections = listOfNotNull(firstVector, secondVector)
            .filter { it.magnitude >= 0.02f }
        if (knownDirections.isEmpty()) return true

        return knownDirections.any { direction ->
            direction.cosineWith(transition) >= config.minMovementDirectionCosine
        }
    }

    private fun mergeTracks(primary: PoseTrack, secondary: PoseTrack): PoseTrack {
        val frames = (primary.frames + secondary.frames)
            .groupBy { it.tMs }
            .map { (_, framesAtTime) ->
                framesAtTime.maxByOrNull { it.landmarks.averageVisibility() } ?: framesAtTime.first()
            }
            .sortedBy { it.tMs }
        return primary.copy(
            frames = frames,
            avgVisibility = frames.averageTrackVisibility()
        )
    }

    private fun choosePrimaryTrack(first: PoseTrack, second: PoseTrack): PoseTrack {
        if (first.frames.size != second.frames.size) {
            return if (first.frames.size > second.frames.size) first else second
        }
        if (first.avgVisibility != second.avgVisibility) {
            return if (first.avgVisibility > second.avgVisibility) first else second
        }
        return if (first.firstTimeMs() <= second.firstTimeMs()) first else second
    }

    private fun orderByTime(first: PoseTrack, second: PoseTrack): Pair<PoseTrack, PoseTrack> {
        return if (first.lastTimeMs() <= second.firstTimeMs()) {
            first to second
        } else {
            second to first
        }
    }

    private fun overlapRatio(first: PoseTrack, second: PoseTrack): Float {
        val overlapStart = maxOf(first.firstTimeMs(), second.firstTimeMs())
        val overlapEnd = minOf(first.lastTimeMs(), second.lastTimeMs())
        val overlapMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
        val shorterDuration = minOf(first.durationMs(), second.durationMs()).coerceAtLeast(frameStepMs)
        return overlapMs.toFloat() / shorterDuration.toFloat()
    }

    private fun PoseTrack.movementVector(): Vector? {
        val first = firstReliableBounds() ?: return null
        val last = lastReliableBounds() ?: return null
        return Vector(
            x = last.centerX - first.centerX,
            y = last.centerY - first.centerY
        )
    }

    private fun PoseTrack.firstReliableBounds(): PoseBounds? {
        return frames.sortedBy { it.tMs }.firstNotNullOfOrNull { it.landmarks.poseBounds() }
    }

    private fun PoseTrack.lastReliableBounds(): PoseBounds? {
        return frames.sortedByDescending { it.tMs }.firstNotNullOfOrNull { it.landmarks.poseBounds() }
    }

    private fun PoseTrack.firstTimeMs(): Long = frames.minOfOrNull { it.tMs } ?: 0L

    private fun PoseTrack.lastTimeMs(): Long = frames.maxOfOrNull { it.tMs } ?: 0L

    private fun PoseTrack.durationMs(): Long = (lastTimeMs() - firstTimeMs()).coerceAtLeast(frameStepMs)

    private fun List<PoseFrame>.averageTrackVisibility(): Float {
        if (isEmpty()) return 0f
        return map { it.landmarks.averageVisibility() }.average().toFloat()
    }

    private fun centerDistance(first: PoseBounds, second: PoseBounds): Float {
        return hypot(
            (first.centerX - second.centerX).toDouble(),
            (first.centerY - second.centerY).toDouble()
        ).toFloat()
    }

    private fun sizeRatio(first: PoseBounds, second: PoseBounds): Float {
        val minArea = minOf(first.area, second.area).coerceAtLeast(1e-4f)
        val maxArea = max(first.area, second.area)
        return maxArea / minArea
    }

    private data class Vector(
        val x: Float,
        val y: Float
    ) {
        val magnitude: Float = sqrt((x * x) + (y * y))

        fun cosineWith(other: Vector): Float {
            val denominator = magnitude * other.magnitude
            if (denominator <= 1e-6f) return 1f
            return ((x * other.x) + (y * other.y)) / denominator
        }
    }
}
