package com.example.chorevo.danceactivity

import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseTemplate

// Practice modes and step-by-step segments. Pure logic, shared by
// PracticeViewModel and DanceARPracticeScreen.

enum class PracticeMode {
    FULL_ROUTINE,
    STEP_BY_STEP
}

data class PracticeStepSegment(
    val index: Int,
    val total: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(1L)

    val title: String
        get() = "Step ${index + 1} of $total"
}

internal fun derivePracticeStepSegments(template: PoseTemplate?): List<PracticeStepSegment> {
    val source = template ?: return emptyList()
    val frames = source.frames.sortedBy { it.tMs }
    if (frames.size < 2) return emptyList()

    val framesPerStep = (source.fps.coerceAtLeast(1) * 4).coerceAtLeast(4)
    val minLastStepFrames = (source.fps.coerceAtLeast(1) * 2).coerceAtLeast(2)
    val chunks = frames.chunked(framesPerStep).toMutableList()

    if (chunks.size > 1 && chunks.last().size < minLastStepFrames) {
        val trailingChunk = chunks.removeAt(chunks.lastIndex)
        chunks[chunks.lastIndex] = chunks.last() + trailingChunk
    }

    val total = chunks.size
    return chunks.mapIndexed { index, chunk ->
        val startMs = chunk.first().tMs
        val rawEndMs = chunk.last().tMs
        val endMs = if (rawEndMs > startMs) rawEndMs else startMs + 1L
        PracticeStepSegment(
            index = index,
            total = total,
            startMs = startMs,
            endMs = endMs
        )
    }
}

internal fun sliceTemplateForStep(
    template: PoseTemplate?,
    segment: PracticeStepSegment
): PoseTemplate? {
    val source = template ?: return null
    val normalizedStart = segment.startMs

    fun rebaseFrames(frames: List<PoseFrame>): List<PoseFrame> {
        return frames
            .filter { it.tMs in segment.startMs..segment.endMs }
            .map { it.copy(tMs = (it.tMs - normalizedStart).coerceAtLeast(0L)) }
    }

    val segmentFrames = rebaseFrames(source.frames)
    if (segmentFrames.isEmpty()) return null

    val segmentTracks = source.tracks.mapNotNull { track ->
        val rebased = rebaseFrames(track.frames)
        if (rebased.isEmpty()) null else track.copy(frames = rebased)
    }

    val segmentHandTracks = source.handTracks.mapNotNull { track ->
        val rebased = track.frames
            .filter { it.tMs in segment.startMs..segment.endMs }
            .map { it.copy(tMs = (it.tMs - normalizedStart).coerceAtLeast(0L)) }
        if (rebased.isEmpty()) null else track.copy(frames = rebased)
    }

    return source.copy(
        frames = segmentFrames,
        tracks = segmentTracks,
        handTracks = segmentHandTracks
    )
}

