package com.example.chorevo.videouploads.ViewModel

import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseTemplate

data class VideoUploadsUiState(
    // Data
    val myUploads: List<DanceLibraryModel> = emptyList(),

    // Loading flags
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,

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
    val description: String = item.description
) {
    val canSave: Boolean
        get() = title.isNotBlank() && danceType.isNotBlank() && description.isNotBlank()
}

data class DancerReviewUiState(
    val isAnalyzing: Boolean = false,
    val progress: Float? = null,
    val template: PoseTemplate? = null,
    val selectedTrackIds: Set<String> = emptySet(),
    val confirmed: Boolean = false,
    val errorMessage: String? = null
) {
    val detectedCount: Int
        get() = template?.availableTracks()?.size ?: 0

    val selectedCount: Int
        get() = selectedTrackIds.size

    val canConfirm: Boolean
        get() = template != null && selectedTrackIds.isNotEmpty()
}

data class UploadDraft(
    val title: String = "",
    val danceType: String = "",
    val description: String = "",
    val selectedVideoUri: Uri? = null,
    val selectedVideoLabel: String? = null
) {
    val canUpload: Boolean
        get() = title.isNotBlank() &&
                danceType.isNotBlank() &&
                description.isNotBlank() &&
                selectedVideoUri != null
}
