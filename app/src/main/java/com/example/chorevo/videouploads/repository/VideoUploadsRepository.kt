package com.example.chorevo.videouploads.repository

import android.content.Context
import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.example.chorevo.videouploads.VideoCompressor
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID
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
        fps: Int = 2,
        onProgress: ((Float) -> Unit)? = null,
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

            // 1) Generate pose template from ORIGINAL video
            val template: PoseTemplate? = if (generateTemplate) {
                val templateRes = VideoPoseTemplateGenerator.generate(
                    context = ctx,
                    videoUriString = videoUri.toString(),
                    fps = fps,
                    onProgress = onProgress
                )

                templateRes.fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        onTemplateFailure?.invoke(e)
                        if (continueWithoutTemplateOnFailure) null else throw e
                    }
                )
            } else {
                null
            }

            val danceId = UUID.randomUUID().toString()

            // 2) Compress video
            compressedFile = VideoCompressor.compressVideo(
                context = ctx,
                inputUri = videoUri,
                outputHeight = 480,   // use 720 if 480 is too blurry
                maxFrameRate = 24     // use 30 if needed
            )

            // 3) Compare sizes
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

            // 4) Upload smaller file
            val videoRef = storage.reference.child("danceVideos/$uid/$danceId.mp4")

            val metadata = StorageMetadata.Builder()
                .setContentType("video/mp4")
                .build()

            videoRef.putFile(uploadUri, metadata).await()
            val videoUrl = videoRef.downloadUrl.await().toString()

            // 5) Save Firestore doc
            val doc = DanceLibraryModel(
                danceId = danceId,
                title = title.trim(),
                description = description.trim(),
                danceType = danceType.trim(),
                educatorUid = uid,
                educatorName = educatorName.trim(),
                videoUrl = videoUrl,
                enabled = true,
                template = template
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
                        "storagePath" to "danceVideos/$uid/$danceId.mp4"
                    ),
                    SetOptions.merge()
                )
                .await()

            Result.success(danceId)

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            compressedFile?.takeIf { it.exists() }?.delete()
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
            runCatching { videoRef.delete().await() }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
