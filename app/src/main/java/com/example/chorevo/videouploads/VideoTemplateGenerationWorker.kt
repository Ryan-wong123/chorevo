package com.example.chorevo.videouploads

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.chorevo.R
import com.example.chorevo.notification.NotificationChannels
import com.example.chorevo.posedetection.PoseTemplateJson
import com.example.chorevo.posedetection.PoseTemplateStorage
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Analyses an uploaded dance video in the background and saves its pose template.
 * Runs as a foreground worker (with a notification) so Android doesn't stop a
 * long analysis part-way.
 */
@HiltWorker
class VideoTemplateGenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val templateGenerator: VideoPoseTemplateGenerator
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DANCE_ID = "dance_id"
        const val KEY_STORAGE_PATH = "storage_path"
        const val KEY_FPS = "template_fps"
        const val KEY_COLLECT_HAND_LANDMARKS = "collect_hand_landmarks"
        private const val NOTIFICATION_ID = 4_201
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            NotificationChannels.DANCE_ANALYSIS_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("Analysing your dance")
            .setContentText("Finding the dancers' moves. This can take a few minutes.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val danceId = inputData.getString(KEY_DANCE_ID) ?: return Result.failure()
        val storagePath = inputData.getString(KEY_STORAGE_PATH) ?: return Result.failure()
        val fps = inputData.getInt(KEY_FPS, 5).coerceAtLeast(1)
        val collectHandLandmarks = inputData.getBoolean(KEY_COLLECT_HAND_LANDMARKS, true)
        val ownerUid = PoseTemplateStorage.ownerUidFromVideoPath(storagePath) ?: return Result.failure()

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
            // Best effort: if the app may not start a foreground service right now,
            // the analysis still runs as a normal background worker.
            runCatching { setForeground(getForegroundInfo()) }
            updateStatus("processing")

            storage.reference
                .child(storagePath)
                .getFile(tempFile)
                .await()

            val templateResult = templateGenerator.generate(
                videoUriString = Uri.fromFile(tempFile).toString(),
                fps = fps,
                collectHandLandmarks = collectHandLandmarks
            )

            templateResult.fold(
                onSuccess = { template ->
                    val templateStoragePath = PoseTemplateStorage.jsonPath(ownerUid, danceId)
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
