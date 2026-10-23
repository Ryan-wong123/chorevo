package com.example.chorevo.videouploads.viewmodel

import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseAnalysisWarning
import com.example.chorevo.posedetection.PoseTemplate

data class VideoUploadsUiState(
    // Data
    val myUploads: List<DanceLibraryModel> = emptyList(),

    // Loading flags
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    /** Uploads whose public/private setting is being saved. */
    val updatingVisibilityIds: Set<String> = emptySet(),

    // Upload Progress
    val uploadProgress: Float? = null,
    val uploadStageLabel: String? = null,

    // Dialog state
    val previewItem: DanceLibraryModel? = null,
    val editDraft: EditDraft? = null,
    val deleteItem: DanceLibraryModel? = null,
    val uploadDraft: UploadDraft = UploadDraft(),
    val dancerReview: DancerReviewUiState = DancerReviewUiState(),
    val showVideoSourceDialog: Boolean = false,

    // One-off message (UI can display and then clear)
    val statusMessage: String? = null
) {
    val busy: Boolean get() = refreshing || saving || deleting
}

data class EditDraft(
    val item: DanceLibraryModel,
    val title: String = item.title,
    val danceType: String = item.danceType,
    val description: String = item.description,
    val visibility: String = item.visibility
) {
    val canSave: Boolean
        get() = title.isNotBlank() && danceType.isNotBlank() && description.isNotBlank()
}

data class DancerReviewUiState(
    val isAnalyzing: Boolean = false,
    val progress: Float? = null,
    val template: PoseTemplate? = null,
    val detectedDancerCount: Int = 0,
    val selectedTrackIds: Set<String> = emptySet(),
    val defaultTrackId: String? = null,
    val confirmed: Boolean = false,
    val errorMessage: String? = null
) {
    val detectedCount: Int
        get() = detectedDancerCount

    val selectedCount: Int
        get() = selectedTrackIds.size

    val warningMessages: List<String>
        get() = template?.qualityWarnings.toCreatorWarningMessages()

    val canConfirm: Boolean
        get() = template != null &&
                selectedTrackIds.isNotEmpty() &&
                defaultTrackId in selectedTrackIds
}

internal fun List<PoseAnalysisWarning>?.toCreatorWarningMessages(): List<String> {
    return orEmpty()
        .map { warning -> warning.toCreatorWarningMessage() }
        .distinct()
}

private fun PoseAnalysisWarning.toCreatorWarningMessage(): String {
    val normalizedCode = code.lowercase()
    return when {
        "duplicate" in normalizedCode ->
            "Possible duplicate dancer tracks were detected. Review the dancer options before uploading."
        "lowlight" in normalizedCode || "light" in normalizedCode ->
            "The video may be dim, so some poses may be less accurate."
        "missing" in normalizedCode || "crop" in normalizedCode || "unavailable" in normalizedCode ->
            "Some frames could not be read clearly. Missing poses will stay marked as low confidence."
        "confidence" in normalizedCode || "landmark" in normalizedCode || "inferred" in normalizedCode ->
            "Some dancer tracks have low-confidence pose points. Review the selected dancer before uploading."
        else -> message.takeIf { it.isNotBlank() }
            ?: "Review the detected dancers before uploading."
    }
}

data class UploadDraft(
    val title: String = "",
    val danceType: String = "",
    val description: String = "",
    val selectedVideoUri: Uri? = null,
    val selectedVideoLabel: String? = null,
    val visibility: String = DanceLibraryModel.VISIBILITY_PUBLIC,
    val coverFrameMs: Long = DanceLibraryModel.DEFAULT_COVER_FRAME_MS
) {
    val canUpload: Boolean
        get() = title.isNotBlank() &&
                danceType.isNotBlank() &&
                description.isNotBlank() &&
                selectedVideoUri != null
}
