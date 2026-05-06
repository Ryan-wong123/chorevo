package com.example.chorevo.videouploads.repository

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoUploadsRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage
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
        generateTemplate: Boolean = true,
        confirmedTemplate: PoseTemplate? = null,
        fps: Int = 2,
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

            // 4) Save Firestore doc immediately, then build template in background.
            onStageChanged?.invoke("Saving details")
            onProgress?.invoke(0.98f)
            val doc = DanceLibraryModel(
                danceId = danceId,
                title = title.trim(),
                description = description.trim(),
                danceType = danceType.trim(),
                educatorUid = uid,
                educatorName = educatorName.trim(),
                videoUrl = videoUrl,
                enabled = true
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
                        "templateGenerationStatus" to when {
                            confirmedTemplate != null -> "saving"
                            generateTemplate -> "queued"
                            else -> "skipped"
                        },
                        "templateReviewStatus" to if (confirmedTemplate != null) "confirmed" else ""
                    ),
                    SetOptions.merge()
                )
                .await()

            if (confirmedTemplate != null) {
                onStageChanged?.invoke("Saving confirmed dancer template")
                val templateStoragePath = PoseTemplateStorage.jsonPath(danceId)
                val templateJsonBytes = PoseTemplateJson
                    .toJson(confirmedTemplate)
                    .toByteArray(Charsets.UTF_8)

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
                    context = ctx,
                    danceId = danceId,
                    storagePath = storagePath,
                    fps = fps
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
        context: Context,
        danceId: String,
        storagePath: String,
        fps: Int
    ) {
        val workRequest = OneTimeWorkRequestBuilder<VideoTemplateGenerationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(VideoTemplateGenerationWorker.KEY_DANCE_ID, danceId)
                    .putString(VideoTemplateGenerationWorker.KEY_STORAGE_PATH, storagePath)
                    .putInt(VideoTemplateGenerationWorker.KEY_FPS, fps)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }

    private suspend fun uploadFileWithProgress(
        videoRef: com.google.firebase.storage.StorageReference,
        uploadUri: Uri,
        metadata: StorageMetadata,
        baseProgress: Float,
        maxProgress: Float,
        onProgress: ((Float) -> Unit)?
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val uploadTask = videoRef.putFile(uploadUri, metadata)

        uploadTask
            .addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.takeIf { it > 0L } ?: return@addOnProgressListener
                val fraction = snapshot.bytesTransferred.toFloat() / total.toFloat()
                val scaled = baseProgress + (maxProgress - baseProgress) * fraction.coerceIn(0f, 1f)
                onProgress?.invoke(scaled)
            }
            .addOnSuccessListener {
                if (cont.isActive) cont.resume(Unit)
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }

        cont.invokeOnCancellation {
            uploadTask.cancel()
        }
    }

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
        description: String
    ): Result<Unit> {
        return try {
            db.collection("danceLibrary")
                .document(danceId)
                .set(
                    mapOf(
                        "title" to title.trim(),
                        "danceType" to danceType.trim(),
                        "description" to description.trim()
                    ),
                    SetOptions.merge()
                )
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

            db.collection("danceLibrary")
                .document(danceId)
                .delete()
                .await()

            val videoRef = storage.reference.child("danceVideos/$uid/$danceId.mp4")
            val templateRef = storage.reference.child(PoseTemplateStorage.jsonPath(danceId))
            runCatching { videoRef.delete().await() }
            runCatching { templateRef.delete().await() }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
