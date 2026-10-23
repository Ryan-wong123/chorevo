package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.videouploads.VideoUploadActivityScreen
import com.example.chorevo.videouploads.viewmodel.VideoUploadsViewModel

/** Renders the upload screen for adding a new dance video. */
@Composable
fun UploadRoute(
    onBack: () -> Unit,
    vm: VideoUploadsViewModel
) {
    VideoUploadActivityScreen(
        videoUploadVm = vm,
        onBack = onBack
    )
}
