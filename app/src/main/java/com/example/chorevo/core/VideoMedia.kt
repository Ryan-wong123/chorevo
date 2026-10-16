package com.example.chorevo.core

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared helpers for uploading videos and their cover thumbnails. */
object VideoMedia {

    /**
     * JPEG of the frame at [timeMs] in a local video (the first frame if that
     * fails), scaled so its longer side is at most [maxSide] px.
     */
    suspend fun coverJpeg(
        context: Context,
        videoUri: Uri,
        timeMs: Long,
        maxSide: Int = 720,
        quality: Int = 82
    ): ByteArray = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val frame = retriever.getFrameAtTime(timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
                ?: error("No frame at ${timeMs}ms")
            val scale = maxSide.toFloat() / maxOf(frame.width, frame.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            } else {
                frame
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            retriever.release()
        }
    }

    /**
     * Uploads [uri] to this reference, reporting progress mapped onto
     * [baseProgress]..[maxProgress]. Cancelling the coroutine cancels the upload.
     */
    suspend fun StorageReference.putFileWithProgress(
        uri: Uri,
        metadata: StorageMetadata,
        baseProgress: Float = 0f,
        maxProgress: Float = 1f,
        onProgress: ((Float) -> Unit)? = null
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val task = putFile(uri, metadata)
        task
            .addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.takeIf { it > 0L } ?: return@addOnProgressListener
                val fraction = snapshot.bytesTransferred.toFloat() / total.toFloat()
                onProgress?.invoke(baseProgress + (maxProgress - baseProgress) * fraction.coerceIn(0f, 1f))
            }
            .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
            .addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        cont.invokeOnCancellation { task.cancel() }
    }
}

/**
 * Whether [url] is a download URL from this app's Firebase Storage. Videos
 * and covers that other users put in posts are only loaded from there, never
 * from an arbitrary host (tracking) or a local scheme such as file:// or
 * content:// (which the player would otherwise open).
 */
fun isTrustedMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host == "firebasestorage.googleapis.com"
}
