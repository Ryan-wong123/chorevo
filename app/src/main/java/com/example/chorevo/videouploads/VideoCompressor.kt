package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Re-encodes a video smaller with Media3 Transformer. Call from the main
 * thread (Transformer needs a Looper); the work itself runs in the background.
 */
@Singleton
class VideoCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Compresses [inputUri] into a new file in the cache folder. */
    @OptIn(UnstableApi::class)
    suspend fun compressVideo(
        inputUri: Uri,
        outputHeight: Int = 480,
        maxFrameRate: Int = 24
    ): File = suspendCancellableCoroutine { cont ->

        val outputFile = File(
            context.cacheDir,
            "compressed_${System.currentTimeMillis()}.mp4"
        )

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setFrameRate(maxFrameRate)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForHeight(outputHeight))
                )
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {

                /** Handles completed events. */
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    if (cont.isActive) {
                        cont.resume(outputFile)
                    }
                }

                /** Handles error events. */
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (cont.isActive) {
                        cont.resumeWithException(exportException)
                    }
                }
            })
            .build()

        transformer.start(editedMediaItem, outputFile.path)

        cont.invokeOnCancellation {
            transformer.cancel()
        }
    }
}
