package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Thumbnails of each detected dancer, cut from the frame of the template's
// selection preview. Used by the dancer pickers on the upload and practice screens.

/** Thumbnails for a published dance's video at [videoUrl]. */
suspend fun loadDancerThumbnails(
    videoUrl: String,
    selectionPreview: PoseSelectionPreview?
): Map<String, Bitmap> {
    if (videoUrl.isBlank()) return emptyMap()
    return loadDancerThumbnails(selectionPreview) { it.setDataSource(videoUrl, emptyMap()) }
}

/** Thumbnails for a local video being uploaded. */
suspend fun loadDancerThumbnails(
    context: Context,
    videoUri: Uri,
    selectionPreview: PoseSelectionPreview?
): Map<String, Bitmap> = loadDancerThumbnails(selectionPreview) { it.setDataSource(context, videoUri) }

private suspend fun loadDancerThumbnails(
    selectionPreview: PoseSelectionPreview?,
    setDataSource: (MediaMetadataRetriever) -> Unit
): Map<String, Bitmap> = withContext(Dispatchers.IO) {
    if (selectionPreview == null || selectionPreview.candidates.isEmpty()) {
        return@withContext emptyMap()
    }

    val retriever = MediaMetadataRetriever()
    try {
        setDataSource(retriever)
        val frame = retriever.getFrameAtTime(
            selectionPreview.timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return@withContext emptyMap()

        selectionPreview.candidates.associate { candidate ->
            candidate.trackId to cropDancerThumbnail(frame, candidate.bounds)
        }
    } catch (_: Exception) {
        emptyMap()
    } finally {
        runCatching { retriever.release() }
    }
}

private fun cropDancerThumbnail(source: Bitmap, bounds: PoseBounds): Bitmap {
    val left = (bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)

    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    return Bitmap.createScaledBitmap(cropped, 160, 220, true)
}
