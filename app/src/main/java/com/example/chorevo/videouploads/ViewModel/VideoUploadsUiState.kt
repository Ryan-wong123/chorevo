package com.example.chorevo.videouploads.ViewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel

data class VideoUploadsUiState(
    // Data
    val myUploads: List<DanceLibraryModel> = emptyList(),

    // Loading flags
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,

    // Upload Progress
    val uploadProgress: Float? = null,

    // Dialog state
    val previewItem: DanceLibraryModel? = null,
    val editDraft: EditDraft? = null,
    val deleteItem: DanceLibraryModel? = null,

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