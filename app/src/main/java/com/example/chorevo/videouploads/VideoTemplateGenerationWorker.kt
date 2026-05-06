package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.chorevo.posedetection.PoseTemplateJson
import com.example.chorevo.posedetection.PoseTemplateStorage
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.File

class VideoTemplateGenerationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DANCE_ID = "dance_id"
        const val KEY_STORAGE_PATH = "storage_path"
        const val KEY_FPS = "template_fps"
    }

    override suspend fun doWork(): Result {
        val danceId = inputData.getString(KEY_DANCE_ID) ?: return Result.failure()
        val storagePath = inputData.getString(KEY_STORAGE_PATH) ?: return Result.failure()
        val fps = inputData.getInt(KEY_FPS, 15).coerceAtLeast(1)

        val firestore = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()
        val tempFile = File(
            applicationContext.cacheDir,
            "template_source_${danceId}_${System.currentTimeMillis()}.mp4"
        )

        suspend fun updateStatus(status: String, error: String? = null) {
            val payload = mutableMapOf<String, Any>(
                "templateGenerationStatus" to status
            )
            if (error != null) {
                payload["templateGenerationError"] = error
            }
            firestore.collection("danceLibrary")
                .document(danceId)
                .set(payload, SetOptions.merge())
                .await()
        }

        return try {
            updateStatus("processing")

            storage.reference
                .child(storagePath)
                .getFile(tempFile)
                .await()

            val templateResult = VideoPoseTemplateGenerator.generate(
                context = applicationContext,
                videoUriString = Uri.fromFile(tempFile).toString(),
                fps = fps
            )

            templateResult.fold(
                onSuccess = { template ->
                    val templateStoragePath = PoseTemplateStorage.jsonPath(danceId)
                    val templateJsonBytes = PoseTemplateJson
                        .toJson(template)
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

                    firestore.collection("danceLibrary")
                        .document(danceId)
                        .set(
                            mapOf(
                                "template" to FieldValue.delete(),
                                "templateStoragePath" to templateStoragePath,
                                "templateGenerationStatus" to "ready",
                                "templateGenerationError" to "",
                                "dancerCount" to template.availableTracks().size
                            ),
                            SetOptions.merge()
                        )
                        .await()

                    Result.success()
                },
                onFailure = { error ->
                    if (runAttemptCount < 2) {
                        Result.retry()
                    } else {
                        updateStatus(
                            status = "failed",
                            error = error.message ?: "Template generation failed"
                        )
                        Result.failure()
                    }
                }
            )
        } catch (error: Exception) {
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                runCatching {
                    updateStatus(
                        status = "failed",
                        error = error.message ?: "Template generation failed"
                    )
                }
                Result.failure()
            }
        } finally {
            tempFile.takeIf { it.exists() }?.delete()
        }
    }
}
