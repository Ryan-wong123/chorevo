package com.example.chorevo.videouploads.ViewModel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
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
                    )
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
                )
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
                )
            )
        }
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
        val videoUri = draft.selectedVideoUri ?: return
        if (!draft.canUpload) return

        viewModelScope.launch {
            mutate { it.copy(saving = true, statusMessage = null, uploadProgress = null) }

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
                    fps = fps,
                    onProgress = { progress ->
                        mutate { s -> s.copy(uploadProgress = progress) }
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
                                statusMessage = "Uploaded",
                                uploadDraft = UploadDraft(),
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
                mutate { it.copy(saving = false, uploadProgress = null) }
            }
        }
    }
}
