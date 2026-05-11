package com.example.chorevo.videouploads.ViewModel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.posedetection.PersonDetectorConfig
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoUploadsViewModel @Inject constructor(
    val authRepository: FirebaseAuthRepository,
    val videoUploadRepository: VideoUploadsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoUploadsUiState())
    val uiState: StateFlow<VideoUploadsUiState> = _uiState.asStateFlow()

    init {
        refreshMyUploads()
    }

    /** Applies a reducer to update the current UI state. */
    private inline fun mutate(reducer: (VideoUploadsUiState) -> VideoUploadsUiState) {
        _uiState.value = reducer(_uiState.value)
    }

    /** Clears status. */
    fun clearStatus() {
        mutate { it.copy(statusMessage = null) }
    }

    /** Initializes the upload draft with a preselected video once. */
    fun initializeUploadDraft(initialVideoUri: Uri?) {
        if (initialVideoUri == null) return
        mutate { state ->
            if (state.uploadDraft.selectedVideoUri != null) {
                state
            } else {
                state.copy(
                    uploadDraft = state.uploadDraft.copy(
                        selectedVideoUri = initialVideoUri,
                        selectedVideoLabel = initialVideoUri.lastPathSegment
                    ),
                    dancerReview = DancerReviewUiState()
                )
            }
        }
    }

    /** Handles upload title changes. */
    fun uploadTitleChanged(newTitle: String) {
        mutate { it.copy(uploadDraft = it.uploadDraft.copy(title = newTitle)) }
    }

    /** Handles upload dance type changes. */
    fun uploadDanceTypeChanged(newDanceType: String) {
        mutate { it.copy(uploadDraft = it.uploadDraft.copy(danceType = newDanceType)) }
    }

    /** Handles upload description changes. */
    fun uploadDescriptionChanged(newDescription: String) {
        mutate { it.copy(uploadDraft = it.uploadDraft.copy(description = newDescription)) }
    }

    /** Opens the upload video source chooser. */
    fun openVideoSourceDialog() {
        mutate { it.copy(showVideoSourceDialog = true) }
    }

    /** Closes the upload video source chooser. */
    fun dismissVideoSourceDialog() {
        mutate { it.copy(showVideoSourceDialog = false) }
    }

    /** Stores a picked gallery video. */
    fun selectGalleryVideo(uri: Uri?) {
        if (uri == null) return
        mutate {
            it.copy(
                uploadDraft = it.uploadDraft.copy(
                    selectedVideoUri = uri,
                    selectedVideoLabel = uri.lastPathSegment ?: "Gallery video"
                ),
                dancerReview = DancerReviewUiState()
            )
        }
    }

    /** Stores a freshly recorded video. */
    fun selectRecordedVideo(uri: Uri?) {
        if (uri == null) return
        mutate {
            it.copy(
                uploadDraft = it.uploadDraft.copy(
                    selectedVideoUri = uri,
                    selectedVideoLabel = "Live recording"
                ),
                dancerReview = DancerReviewUiState()
            )
        }
    }

    /** Runs high-recall pose analysis so the uploader can confirm dancer tracks before upload. */
    fun analyzeDancers(context: Context, fps: Int = 8) {
        val videoUri = _uiState.value.uploadDraft.selectedVideoUri ?: return

        viewModelScope.launch {
            mutate {
                it.copy(
                    dancerReview = DancerReviewUiState(
                        isAnalyzing = true,
                        progress = 0f
                    ),
                    statusMessage = null
                )
            }

            val result = VideoPoseTemplateGenerator.generate(
                context = context,
                videoUriString = videoUri.toString(),
                fps = fps,
                profile = VideoPoseTemplateGenerator.GenerationProfile.HIGH_RECALL,
                personDetectorConfig = PersonDetectorConfig(enabled = true),
                onProgress = { progress ->
                    mutate { state ->
                        state.copy(
                            dancerReview = state.dancerReview.copy(progress = progress)
                        )
                    }
                }
            )

            result.fold(
                onSuccess = { template ->
                    val tracks = template.availableTracks()
                    val previewableTrackIds = template.previewableTrackIds()
                    mutate {
                        it.copy(
                            dancerReview = DancerReviewUiState(
                                template = template,
                                selectedTrackIds = tracks
                                    .map { track -> track.trackId }
                                    .filter { trackId -> trackId in previewableTrackIds }
                                    .toSet()
                            )
                        )
                    }
                },
                onFailure = { error ->
                    mutate {
                        it.copy(
                            dancerReview = DancerReviewUiState(
                                errorMessage = error.message ?: "Unable to detect dancers in this video"
                            )
                        )
                    }
                }
            )
        }
    }

    /** Includes or excludes one detected dancer from the confirmed upload template. */
    fun toggleDetectedDancer(trackId: String) {
        mutate { state ->
            val review = state.dancerReview
            if (review.template == null || review.confirmed) return@mutate state
            val selected = review.selectedTrackIds.toMutableSet()
            if (!selected.add(trackId)) {
                selected.remove(trackId)
            }
            state.copy(dancerReview = review.copy(selectedTrackIds = selected))
        }
    }

    /** Confirms that the selected AI tracks are the dancer options learners should see. */
    fun confirmDancerReview() {
        mutate { state ->
            val review = state.dancerReview
            if (!review.canConfirm) return@mutate state
            val previewableTrackIds = review.template?.previewableTrackIds().orEmpty()
            val selectedTrackIds = review.selectedTrackIds.intersect(previewableTrackIds)
            if (selectedTrackIds.isEmpty()) {
                return@mutate state.copy(
                    dancerReview = review.copy(
                        errorMessage = "No previewable dancer tracks are selected."
                    )
                )
            }
            state.copy(
                dancerReview = review.copy(
                    selectedTrackIds = selectedTrackIds,
                    confirmed = true,
                    errorMessage = null
                )
            )
        }
    }

    fun clearDancerReview() {
        mutate { it.copy(dancerReview = DancerReviewUiState()) }
    }

    /** Refreshes my uploads. */
    fun refreshMyUploads() {
        viewModelScope.launch {
            mutate { it.copy(refreshing = true) }
            try {
                val profile = authRepository.getCurrentUserProfile()
                val uid = profile?.uid

                if (uid.isNullOrBlank()) {
                    mutate {
                        it.copy(
                            myUploads = emptyList(),
                            statusMessage = "Not logged in."
                        )
                    }
                    return@launch
                }

                val res = videoUploadRepository.loadVideos(uid)
                res.fold(
                    onSuccess = { list ->
                        mutate { it.copy(myUploads = list) }
                    },
                    onFailure = { e ->
                        mutate { it.copy(statusMessage = "Failed to load uploads: ${e.message}") }
                    }
                )
            } finally {
                mutate { it.copy(refreshing = false) }
            }
        }
    }

    /** Opens preview. */
    fun openPreview(item: DanceLibraryModel) {
        mutate { it.copy(previewItem = item) }
    }

    /** Closes preview. */
    fun closePreview() {
        mutate { it.copy(previewItem = null) }
    }

    /** Opens edit. */
    fun openEdit(item: DanceLibraryModel) {
        mutate { it.copy(editDraft = EditDraft(item)) }
    }

    /** Handles dismiss edit. */
    fun dismissEdit() {
        if (_uiState.value.saving) return
        mutate { it.copy(editDraft = null) }
    }

    /** Handles edit title changed. */
    fun editTitleChanged(newTitle: String) {
        mutate { s ->
            val d = s.editDraft ?: return@mutate s
            s.copy(editDraft = d.copy(title = newTitle))
        }
    }

    /** Handles edit dance type changed. */
    fun editDanceTypeChanged(newDanceType: String) {
        mutate { s ->
            val d = s.editDraft ?: return@mutate s
            s.copy(editDraft = d.copy(danceType = newDanceType))
        }
    }

    /** Handles edit description changed. */
    fun editDescriptionChanged(newDesc: String) {
        mutate { s ->
            val d = s.editDraft ?: return@mutate s
            s.copy(editDraft = d.copy(description = newDesc))
        }
    }

    /** Saves edit. */
    fun saveEdit() {
        val draft = _uiState.value.editDraft ?: return
        if (!draft.canSave) return

        viewModelScope.launch {
            mutate { it.copy(saving = true) }
            try {
                val res = videoUploadRepository.updateDance(
                    danceId = draft.item.danceId,
                    title = draft.title,
                    description = draft.description,
                    danceType = draft.danceType
                )

                res.fold(
                    onSuccess = {
                        mutate { it.copy(statusMessage = "Updated", editDraft = null) }
                        refreshMyUploads()
                    },
                    onFailure = { e ->
                        mutate { it.copy(statusMessage = "Update failed: ${e.message}") }
                    }
                )
            } finally {
                mutate { it.copy(saving = false) }
            }
        }
    }


    /** Opens delete. */
    fun openDelete(item: DanceLibraryModel) {
        mutate { it.copy(deleteItem = item) }
    }

    /** Handles dismiss delete. */
    fun dismissDelete() {
        if (_uiState.value.deleting) return
        mutate { it.copy(deleteItem = null) }
    }

    /** Handles confirm delete. */
    fun confirmDelete() {
        val item = _uiState.value.deleteItem ?: return

        viewModelScope.launch {
            mutate { it.copy(deleting = true) }
            try {
                val res = videoUploadRepository.deleteDance(
                    danceId = item.danceId,
                    educatorUid = item.educatorUid
                )

                res.fold(
                    onSuccess = {
                        mutate { it.copy(statusMessage = "Deleted", deleteItem = null) }
                        refreshMyUploads()
                    },
                    onFailure = { e ->
                        mutate { it.copy(statusMessage = "Delete failed: ${e.message}") }
                    }
                )
            } finally {
                mutate { it.copy(deleting = false) }
            }
        }
    }

    /** Uploads dance. */
    fun uploadDance(
        context: Context,
        generateTemplate: Boolean = true,
        fps: Int = 2,
        continueWithoutTemplateOnFailure: Boolean = true
    ) {
        val draft = _uiState.value.uploadDraft
        val dancerReview = _uiState.value.dancerReview
        val videoUri = draft.selectedVideoUri ?: return
        if (!draft.canUpload) return
        if (!dancerReview.confirmed && !generateTemplate) {
            mutate { it.copy(statusMessage = "Analyze and confirm the dancer options before uploading.") }
            return
        }

        val confirmedTemplate = if (dancerReview.confirmed) {
            dancerReview.template?.confirmedTemplate(dancerReview.selectedTrackIds) ?: run {
                mutate { it.copy(statusMessage = "Confirmed dancer template is unavailable.") }
                return
            }
        } else {
            null
        }

        viewModelScope.launch {
            mutate {
                it.copy(
                    saving = true,
                    statusMessage = null,
                    uploadProgress = 0f,
                    uploadStageLabel = "Preparing upload"
                )
            }

            try {
                val profile = authRepository.getCurrentUserProfile()
                val uid = profile?.uid
                val educatorName =
                    profile?.displayName
                        ?: profile?.email
                        ?: "Unknown"

                if (uid.isNullOrBlank()) {
                    mutate { it.copy(statusMessage = "Not logged in.") }
                    return@launch
                }

                val res = videoUploadRepository.uploadDance(
                    context = context,
                    educatorUid = uid,
                    educatorName = educatorName,
                    title = draft.title,
                    danceType = draft.danceType,
                    description = draft.description,
                    videoUri = videoUri,
                    generateTemplate = generateTemplate,
                    confirmedTemplate = confirmedTemplate,
                    fps = fps,
                    onProgress = { progress ->
                        mutate { s -> s.copy(uploadProgress = progress) }
                    },
                    onStageChanged = { stage ->
                        mutate { s -> s.copy(uploadStageLabel = stage) }
                    },
                    onTemplateFailure = { e ->
                         mutate { it.copy(statusMessage = "Template generation failed: ${e.message}") }
                    },
                    continueWithoutTemplateOnFailure = continueWithoutTemplateOnFailure
                )

                res.fold(
                    onSuccess = {
                        mutate {
                            it.copy(
                                statusMessage = confirmedTemplate?.let { template ->
                                    "Uploaded with ${template.availableTracks().size} confirmed dancer option(s)."
                                } ?: "Uploaded. Template generation continues in the background.",
                                uploadDraft = UploadDraft(),
                                dancerReview = DancerReviewUiState(),
                                showVideoSourceDialog = false
                            )
                        }
                        refreshMyUploads()
                    },
                    onFailure = { e ->
                        mutate { it.copy(statusMessage = "Upload failed: ${e.message}") }
                    }
                )
            } finally {
                mutate { it.copy(saving = false, uploadProgress = null, uploadStageLabel = null) }
            }
        }
    }
}

private fun PoseTemplate.confirmedTemplate(selectedTrackIds: Set<String>): PoseTemplate {
    val previewableTrackIds = previewableTrackIds()
    val allowedTrackIds = selectedTrackIds.intersect(previewableTrackIds)
    val selectedTracks = availableTracks()
        .filter { it.trackId in allowedTrackIds }
        .mapIndexed { index, track -> track.copy(label = "Dancer ${index + 1}") }

    val defaultTrack = selectedTracks.firstOrNull()
    val selectedIds = selectedTracks.map { it.trackId }.toSet()
    val filteredPreview = selectionPreview?.copy(
        candidates = selectionPreview.candidates.filter { it.trackId in selectedIds }
    )

    return copy(
        frames = defaultTrack?.frames.orEmpty(),
        tracks = selectedTracks,
        defaultTrackId = defaultTrack?.trackId,
        selectionPreview = filteredPreview
    )
}

private fun PoseTemplate.previewableTrackIds(): Set<String> {
    return selectionPreview
        ?.candidates
        .orEmpty()
        .map { it.trackId }
        .toSet()
}
