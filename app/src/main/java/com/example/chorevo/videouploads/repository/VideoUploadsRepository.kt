package com.example.chorevo.videouploads.repository

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.chorevo.core.Database.di.DefaultDispatcher
import com.example.chorevo.core.VideoMedia
import com.example.chorevo.core.VideoMedia.putFileWithProgress
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTemplateJson
import com.example.chorevo.posedetection.PoseTemplateStorage
import com.example.chorevo.videouploads.VideoTemplateGenerationWorker
import com.example.chorevo.videouploads.VideoCompressor
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoUploadsRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val workManager: WorkManager,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {

    // ============================================================
    // Helpers
    // ============================================================

    /** Returns uri size. */
    private fun getUriSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // ============================================================
    // Upload
    // ============================================================

    /** Uploads dance. */
    suspend fun uploadDance(
        context: Context? = null,
        educatorUid: String?,
        educatorName: String,
        title: String,
        danceType: String,
        description: String,
        videoUri: Uri,
        visibility: String = DanceLibraryModel.VISIBILITY_PUBLIC,
        coverFrameMs: Long = DanceLibraryModel.DEFAULT_COVER_FRAME_MS,
        generateTemplate: Boolean = true,
        confirmedTemplate: PoseTemplate? = null,
        fps: Int = 2,
        collectHandLandmarks: Boolean = true,
        onProgress: ((Float) -> Unit)? = null,
        onStageChanged: ((String) -> Unit)? = null,
        onTemplateFailure: ((Throwable) -> Unit)? = null,
        continueWithoutTemplateOnFailure: Boolean = true
    ): Result<String> {
        var compressedFile: File? = null

        return try {
            val ctx = context
                ?: return Result.failure(
                    IllegalStateException("Context is required for upload/compression")
                )

            val uid = educatorUid?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("Missing educatorUid")
                )

            val danceId = UUID.randomUUID().toString()
            val storagePath = "danceVideos/$uid/$danceId.mp4"

            // 1) Compress video
            onStageChanged?.invoke("Compressing video")
            onProgress?.invoke(0.1f)
            compressedFile = VideoCompressor.compressVideo(
                context = ctx,
                inputUri = videoUri,
                outputHeight = 720,
                maxFrameRate = 24     // use 30 if needed
            )

            // 2) Compare sizes
            val originalSize = getUriSize(ctx, videoUri)
            val compressedSize = compressedFile.length()

            val useCompressed = when {
                compressedSize <= 0L -> false
                originalSize <= 0L -> true
                compressedSize < originalSize -> true
                else -> false
            }

            val uploadUri = if (useCompressed) {
                Uri.fromFile(compressedFile)
            } else {
                videoUri
            }

            val uploadedSize = if (useCompressed) compressedSize else originalSize

            // 3) Upload smaller file
            val videoRef = storage.reference.child(storagePath)

            val metadata = StorageMetadata.Builder()
                .setContentType("video/mp4")
                .build()

            onStageChanged?.invoke("Uploading video")
            uploadFileWithProgress(
                videoRef = videoRef,
                uploadUri = uploadUri,
                metadata = metadata,
                baseProgress = 0.2f,
                maxProgress = 0.95f,
                onProgress = onProgress
            )
            val videoUrl = videoRef.downloadUrl.await().toString()

            // Cover-frame thumbnail for grids and link previews. Best effort:
            // an upload without one still works (grids fall back to the video).
            onStageChanged?.invoke("Saving cover image")
            val thumbnailUrl = runCatching {
                uploadThumbnail(ctx, videoUri, uid, danceId, coverFrameMs)
            }.getOrNull().orEmpty()

            // 4) Save Firestore doc immediately, then build template in background.
            onStageChanged?.invoke("Saving details")
            onProgress?.invoke(0.98f)
            val initialStatus = when {
                confirmedTemplate != null -> "saving"
                generateTemplate -> "queued"
                else -> "skipped"
            }
            val reviewStatus = if (confirmedTemplate != null) "confirmed" else ""
            // The real status goes into the first write. DanceLibraryModel defaults
            // to "ready", which would briefly make the dance show up in the feed.
            val doc = DanceLibraryModel(
                danceId = danceId,
                title = title.trim().take(120),
                description = description.trim().take(2_000),
                danceType = danceType.trim().take(40),
                educatorUid = uid,
                educatorName = educatorName.trim().take(60),
                videoUrl = videoUrl,
                enabled = true,
                templateGenerationStatus = initialStatus,
                templateReviewStatus = reviewStatus,
                visibility = visibility,
                coverFrameMs = coverFrameMs,
                thumbnailUrl = thumbnailUrl
            )

            db.collection("danceLibrary")
                .document(danceId)
                .set(
                    doc.copy(),
                    SetOptions.merge()
                )
                .await()

            // optional debug / analytics fields
            db.collection("danceLibrary")
                .document(danceId)
                .set(
                    mapOf(
                        "originalSizeBytes" to originalSize,
                        "compressedSizeBytes" to compressedSize,
                        "uploadedSizeBytes" to uploadedSize,
                        "wasCompressed" to useCompressed,
                        "storagePath" to storagePath,
                        "templateGenerationStatus" to initialStatus,
                        "templateReviewStatus" to reviewStatus
                    ),
                    SetOptions.merge()
                )
                .await()

            if (confirmedTemplate != null) {
                onStageChanged?.invoke("Saving confirmed dancer template")
                val templateStoragePath = PoseTemplateStorage.jsonPath(uid, danceId)
                // Templates can be several MB; serialise off the main thread.
                val templateJsonBytes = withContext(defaultDispatcher) {
                    PoseTemplateJson
                        .toJson(confirmedTemplate)
                        .toByteArray(Charsets.UTF_8)
                }

                storage.reference
                    .child(templateStoragePath)
                    .putBytes(
                        templateJsonBytes,
                        StorageMetadata.Builder()
                            .setContentType("application/json")
                            .build()
                    )
                    .await()

                db.collection("danceLibrary")
                    .document(danceId)
                    .set(
                        mapOf(
                            "templateStoragePath" to templateStoragePath,
                            "templateGenerationStatus" to "ready",
                            "templateGenerationError" to "",
                            "templateReviewStatus" to "confirmed",
                            "dancerCount" to confirmedTemplate.availableTracks().size
                        ),
                        SetOptions.merge()
                    )
                    .await()
            } else if (generateTemplate) {
                onStageChanged?.invoke("Queuing background analysis")
                enqueueTemplateGeneration(
                    danceId = danceId,
                    storagePath = storagePath,
                    fps = fps,
                    collectHandLandmarks = collectHandLandmarks
                )
            }

            onProgress?.invoke(1f)
            Result.success(danceId)

        } catch (e: Exception) {
            if (!continueWithoutTemplateOnFailure) {
                onTemplateFailure?.invoke(e)
            }
            Result.failure(e)
        } finally {
            compressedFile?.takeIf { it.exists() }?.delete()
        }
    }

    private fun enqueueTemplateGeneration(
        danceId: String,
        storagePath: String,
        fps: Int,
        collectHandLandmarks: Boolean
    ) {
        val workRequest = OneTimeWorkRequestBuilder<VideoTemplateGenerationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(VideoTemplateGenerationWorker.KEY_DANCE_ID, danceId)
                    .putString(VideoTemplateGenerationWorker.KEY_STORAGE_PATH, storagePath)
                    .putInt(VideoTemplateGenerationWorker.KEY_FPS, fps)
                    .putBoolean(
                        VideoTemplateGenerationWorker.KEY_COLLECT_HAND_LANDMARKS,
                        collectHandLandmarks
                    )
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Start promptly; falls back to normal work when the expedited quota is used up.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueue(workRequest)
    }

    private suspend fun uploadFileWithProgress(
        videoRef: com.google.firebase.storage.StorageReference,
        uploadUri: Uri,
        metadata: StorageMetadata,
        baseProgress: Float,
        maxProgress: Float,
        onProgress: ((Float) -> Unit)?
    ) = videoRef.putFileWithProgress(uploadUri, metadata, baseProgress, maxProgress, onProgress)

    // ============================================================
    // My uploads list
    // ============================================================

    /** Loads videos. */
    suspend fun loadVideos(educatorUid: String): Result<List<DanceLibraryModel>> {
        return try {
            val snap = db.collection("danceLibrary")
                .whereEqualTo("educatorUid", educatorUid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()

            Result.success(snap.toObjects(DanceLibraryModel::class.java))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // Update / Delete
    // ============================================================

    /** Updates dance. */
    suspend fun updateDance(
        danceId: String,
        title: String,
        danceType: String,
        description: String,
        visibility: String? = null
    ): Result<Unit> {
        return try {
            val fields = mutableMapOf<String, Any>(
                "title" to title.trim().take(120),
                "danceType" to danceType.trim().take(40),
                "description" to description.trim().take(2_000)
            )
            visibility?.let { fields["visibility"] = it }

            db.collection("danceLibrary")
                .document(danceId)
                .set(
                    fields,
                    SetOptions.merge()
                )
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Sets an upload to [DanceLibraryModel.VISIBILITY_PUBLIC] or [DanceLibraryModel.VISIBILITY_PRIVATE]. */
    suspend fun setVisibility(danceId: String, visibility: String): Result<Unit> {
        return try {
            db.collection("danceLibrary")
                .document(danceId)
                .set(mapOf("visibility" to visibility), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Handles delete dance. */
    suspend fun deleteDance(
        danceId: String,
        educatorUid: String?
    ): Result<Unit> {
        return try {
            val uid = educatorUid?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("Missing educatorUid for delete")
                )

            val danceRef = db.collection("danceLibrary").document(danceId)

            val videoRef = storage.reference.child("danceVideos/$uid/$danceId.mp4")
            val templateRef = storage.reference.child(PoseTemplateStorage.jsonPath(uid, danceId))
            val thumbnailRef = storage.reference.child(thumbnailPath(uid, danceId))
            runCatching { videoRef.delete().await() }
            runCatching { thumbnailRef.delete().await() }
            runCatching { templateRef.delete().await() }

            // Then likes and comments, which Firestore does not delete with the parent.
            for (sub in listOf("likes", "comments")) {
                runCatching {
                    danceRef.collection(sub).get().await().documents.forEach { it.reference.delete().await() }
                }
            }

            danceRef.delete().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun thumbnailPath(uid: String, danceId: String) = "danceThumbnails/$uid/$danceId.jpg"

    /**
     * Grabs the frame at [coverFrameMs] from the local video, scales it to at
     * most [THUMBNAIL_MAX_SIDE] px, uploads it as a JPEG and returns its URL.
     */
    private suspend fun uploadThumbnail(
        context: Context,
        videoUri: Uri,
        uid: String,
        danceId: String,
        coverFrameMs: Long
    ): String {
        val jpeg = VideoMedia.coverJpeg(context, videoUri, coverFrameMs, THUMBNAIL_MAX_SIDE, THUMBNAIL_JPEG_QUALITY)
        val ref = storage.reference.child(thumbnailPath(uid, danceId))
        ref.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        return ref.downloadUrl.await().toString()
    }

    private companion object {
        const val THUMBNAIL_MAX_SIDE = 720
        const val THUMBNAIL_JPEG_QUALITY = 82
    }
}
