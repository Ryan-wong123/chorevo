package com.example.chorevo.posedetection

import com.google.firebase.firestore.PropertyName
import kotlin.math.max
import kotlin.math.min

data class PoseTemplate(
    val fps: Int = 2,
    val frames: List<PoseFrame> = emptyList(),
    val tracks: List<PoseTrack> = emptyList(),
    val defaultTrackId: String? = null,
    val selectionPreview: PoseSelectionPreview? = null
) {
    /** Returns all usable tracks, including a synthetic legacy track for old templates. */
    fun availableTracks(): List<PoseTrack> {
        val persisted = tracks.filter { it.frames.isNotEmpty() }
        if (persisted.isNotEmpty()) return persisted
        if (frames.isEmpty()) return emptyList()
        return listOf(
            PoseTrack(
                trackId = LEGACY_TRACK_ID,
                label = "Dancer 1",
                frames = frames,
                avgVisibility = frames.map { it.landmarks.averageVisibility() }.average().toFloat()
            )
        )
    }

    /** Resolves a track by id, falling back to the default or primary track. */
    fun resolveTrack(trackId: String?): PoseTrack? {
        val available = availableTracks()
        if (available.isEmpty()) return null

        return available.firstOrNull { it.trackId == trackId }
            ?: available.firstOrNull { it.trackId == defaultTrackId }
            ?: available.maxWithOrNull(
                compareBy<PoseTrack> { it.frames.size }
                    .thenBy { it.avgVisibility }
            )
    }

    companion object {
        const val LEGACY_TRACK_ID: String = "legacy_track"
    }
}

data class PoseTrack(
    val trackId: String = "",
    val label: String = "",
    val frames: List<PoseFrame> = emptyList(),
    val avgVisibility: Float = 0f
)

data class PoseSelectionPreview(
    val timeMs: Long = 0L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val candidates: List<PoseSelectionCandidate> = emptyList()
)

data class PoseSelectionCandidate(
    val trackId: String = "",
    val landmarks: List<Float> = emptyList(),
    val bounds: PoseBounds = PoseBounds()
)

data class PoseBounds(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    val area: Float
        get() = width * height

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun iou(other: PoseBounds): Float {
        val intersectLeft = max(left, other.left)
        val intersectTop = max(top, other.top)
        val intersectRight = min(right, other.right)
        val intersectBottom = min(bottom, other.bottom)

        val intersectWidth = (intersectRight - intersectLeft).coerceAtLeast(0f)
        val intersectHeight = (intersectBottom - intersectTop).coerceAtLeast(0f)
        val intersectArea = intersectWidth * intersectHeight
        val union = area + other.area - intersectArea
        if (union <= 0f) return 0f
        return intersectArea / union
    }
}

data class PoseFrame(
    @get:PropertyName("tms")
    @set:PropertyName("tms")
    var tMs: Long = 0L,
    var landmarks: List<Float> = emptyList()
)

/** Computes the average landmark visibility for one flattened pose frame. */
fun List<Float>.averageVisibility(): Float {
    if (size < 4) return 0f
    var sum = 0f
    var count = 0
    for (i in 3 until size step 4) {
        sum += this[i]
        count++
    }
    if (count == 0) return 0f
    return sum / count.toFloat()
}

/** Builds a normalized pose bounds box using visible landmarks only. */
fun List<Float>.poseBounds(visibilityThreshold: Float = 0.3f, padding: Float = 0.05f): PoseBounds? {
    if (size < 4) return null

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var visibleCount = 0

    for (i in indices step 4) {
        val vis = getOrNull(i + 3) ?: continue
        if (vis < visibilityThreshold) continue
        val x = this[i]
        val y = this[i + 1]
        minX = min(minX, x)
        minY = min(minY, y)
        maxX = max(maxX, x)
        maxY = max(maxY, y)
        visibleCount++
    }

    if (visibleCount == 0) return null

    return PoseBounds(
        left = (minX - padding).coerceIn(0f, 1f),
        top = (minY - padding).coerceIn(0f, 1f),
        right = (maxX + padding).coerceIn(0f, 1f),
        bottom = (maxY + padding).coerceIn(0f, 1f)
    )
}
