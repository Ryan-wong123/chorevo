package com.example.chorevo.posedetection

import kotlin.math.max

data class MissingLandmarkInterpolatorConfig(
    val maxGapFrames: Int = 3,
    val maxGapDurationMs: Long = 500L,
    val interpolatedVisibilityCap: Float = 0.20f,
    val minReliableVisibility: Float = 0.15f
)

data class MissingLandmarkInterpolationResult(
    val tracks: List<PoseTrack>,
    val interpolatedFrameCount: Int = 0
)

class MissingLandmarkInterpolator(
    private val fps: Int,
    private val config: MissingLandmarkInterpolatorConfig = MissingLandmarkInterpolatorConfig()
) {
    private val frameStepMs = (1000L / max(1, fps)).coerceAtLeast(1L)

    fun interpolate(tracks: List<PoseTrack>): MissingLandmarkInterpolationResult {
        var insertedCount = 0
        val updatedTracks = tracks.map { track ->
            val frames = track.frames.sortedBy { it.tMs }
            if (frames.size < 2) return@map track

            val updatedFrames = mutableListOf<PoseFrame>()
            for (index in 0 until frames.lastIndex) {
                val start = frames[index]
                val end = frames[index + 1]
                updatedFrames += start

                val inserted = interpolateGap(start, end)
                updatedFrames += inserted
                insertedCount += inserted.size
            }
            updatedFrames += frames.last()

            track.copy(
                frames = updatedFrames,
                avgVisibility = updatedFrames.averageTrackVisibility()
            )
        }

        return MissingLandmarkInterpolationResult(
            tracks = updatedTracks,
            interpolatedFrameCount = insertedCount
        )
    }

    private fun interpolateGap(start: PoseFrame, end: PoseFrame): List<PoseFrame> {
        if (!isReliableEndpoint(start) || !isReliableEndpoint(end)) return emptyList()
        if (start.landmarks.size != end.landmarks.size || start.landmarks.size < 4) return emptyList()

        val gapMs = end.tMs - start.tMs
        if (gapMs <= frameStepMs) return emptyList()
        if (gapMs > config.maxGapDurationMs) return emptyList()

        val missingFrames = ((gapMs / frameStepMs) - 1L).toInt()
        if (missingFrames <= 0 || missingFrames > config.maxGapFrames) return emptyList()

        return (1..missingFrames).map { step ->
            val tMs = start.tMs + (frameStepMs * step)
            val progress = (tMs - start.tMs).toFloat() / gapMs.toFloat()
            PoseFrame(
                tMs = tMs,
                landmarks = interpolateLandmarks(start.landmarks, end.landmarks, progress)
            )
        }
    }

    private fun interpolateLandmarks(
        start: List<Float>,
        end: List<Float>,
        progress: Float
    ): List<Float> {
        val values = ArrayList<Float>(start.size)
        for (index in start.indices step 4) {
            val startX = start[index]
            val startY = start[index + 1]
            val startZ = start[index + 2]
            val startVisibility = start[index + 3]
            val endX = end[index]
            val endY = end[index + 1]
            val endZ = end[index + 2]
            val endVisibility = end[index + 3]
            val endpointVisibility = minOf(startVisibility, endVisibility)

            values += lerp(startX, endX, progress).coerceIn(0f, 1f)
            values += lerp(startY, endY, progress).coerceIn(0f, 1f)
            values += lerp(startZ, endZ, progress)
            values += minOf(endpointVisibility, config.interpolatedVisibilityCap).coerceIn(0f, 1f)
        }
        return values
    }

    private fun isReliableEndpoint(frame: PoseFrame): Boolean {
        return frame.landmarks.averageVisibility() >= config.minReliableVisibility &&
            frame.landmarks.poseBounds(visibilityThreshold = config.minReliableVisibility) != null
    }

    private fun List<PoseFrame>.averageTrackVisibility(): Float {
        if (isEmpty()) return 0f
        return map { it.landmarks.averageVisibility() }.average().toFloat()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + ((end - start) * progress)
    }
}
