package com.example.chorevo.videouploads

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.posedetection.PoseBounds
import com.example.chorevo.posedetection.PoseSelectionPreview
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays the video upload activity screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUploadActivityScreen(
    onBack: () -> Unit,
    initialVideoUri: Uri? = null,
    showPickButton: Boolean = true,
    videoUploadVm: VideoUploadsViewModel
) {
    val context = LocalContext.current
    val uiState by videoUploadVm.uiState.collectAsStateWithLifecycle()
    val uploadDraft = uiState.uploadDraft

    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(initialVideoUri) {
        videoUploadVm.initializeUploadDraft(initialVideoUri)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        videoUploadVm.selectGalleryVideo(uri)
    }

    val videoCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { isCaptured ->
        if (isCaptured) {
            videoUploadVm.selectRecordedVideo(pendingCaptureUri)
        } else if (pendingCaptureUri != null) {
            Toast.makeText(context, "Video recording was cancelled.", Toast.LENGTH_SHORT).show()
        }
        pendingCaptureUri = null
    }

    val uploading = uiState.saving
    val status = uiState.statusMessage

    val brandTeal = Color(0xFF00B8C8)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val secondaryText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    val tealButtonColors = ButtonDefaults.buttonColors(
        containerColor = brandTeal,
        contentColor = Color.White,
        disabledContainerColor = brandTeal.copy(alpha = 0.4f),
        disabledContentColor = Color.White.copy(alpha = 0.6f)
    )

    fun startLiveRecording() {
        val captureUri = VideoCaptureFileProvider.createVideoCaptureUri(context)
        if (captureUri == null) {
            Toast.makeText(
                context,
                "Unable to prepare a video file for recording.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        pendingCaptureUri = captureUri
        videoCaptureLauncher.launch(captureUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload Dance", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = brandTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(surfaceColor)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UploadFormField(
                value = uploadDraft.title,
                onValueChange = videoUploadVm::uploadTitleChanged,
                label = "Title",
                placeholder = "e.g. Beginner Hip Hop Routine",
                icon = Icons.Outlined.Title,
                testTag = "titleField",
                brandTeal = brandTeal
            )

            UploadFormField(
                value = uploadDraft.danceType,
                onValueChange = videoUploadVm::uploadDanceTypeChanged,
                label = "Dance Type",
                placeholder = "e.g. Hip Hop, Salsa, Ballet",
                icon = Icons.Outlined.MusicNote,
                testTag = "danceTypeField",
                brandTeal = brandTeal
            )

            UploadFormField(
                value = uploadDraft.description,
                onValueChange = videoUploadVm::uploadDescriptionChanged,
                label = "Description",
                placeholder = "Describe the moves, difficulty level, or style...",
                icon = Icons.Outlined.Description,
                testTag = "descriptionField",
                brandTeal = brandTeal,
                minLines = 4,
                singleLine = false
            )

            if (showPickButton) {
                VideoPicker(
                    selectedUri = uploadDraft.selectedVideoUri,
                    selectedVideoLabel = uploadDraft.selectedVideoLabel,
                    uploading = uploading,
                    brandTeal = brandTeal,
                    onPick = videoUploadVm::openVideoSourceDialog
                )
            }

            uploadDraft.selectedVideoUri?.let { selectedVideoUri ->
                DancerReviewPanel(
                    videoUri = selectedVideoUri,
                    tracks = uiState.dancerReview.template?.availableTracks().orEmpty(),
                    selectionPreview = uiState.dancerReview.template?.selectionPreview,
                    selectedTrackIds = uiState.dancerReview.selectedTrackIds,
                    confirmed = uiState.dancerReview.confirmed,
                    isAnalyzing = uiState.dancerReview.isAnalyzing,
                    progress = uiState.dancerReview.progress,
                    errorMessage = uiState.dancerReview.errorMessage,
                    brandTeal = brandTeal,
                    onAnalyze = { videoUploadVm.analyzeDancers(context = context, fps = 10) },
                    onToggleTrack = videoUploadVm::toggleDetectedDancer,
                    onConfirm = videoUploadVm::confirmDancerReview,
                    onReset = videoUploadVm::clearDancerReview
                )
            }

            AnimatedVisibility(visible = uploading, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val p = uiState.uploadProgress
                    if (p == null) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = brandTeal,
                            trackColor = brandTeal.copy(alpha = 0.2f)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { p.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = brandTeal,
                            trackColor = brandTeal.copy(alpha = 0.2f)
                        )
                        Text(
                            "${(p * 100).toInt()}% complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                        uiState.uploadStageLabel?.let { stage ->
                            Text(
                                stage,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryText
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = status != null, enter = fadeIn(), exit = fadeOut()) {
                status?.let {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = brandTeal.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = brandTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { videoUploadVm.clearStatus() },
                                colors = ButtonDefaults.textButtonColors(contentColor = brandTeal),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Dismiss", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                enabled = !uploading && uploadDraft.canUpload && uiState.dancerReview.confirmed,
                onClick = {
                    videoUploadVm.uploadDance(
                        context = context,
                        generateTemplate = false,
                        fps = 15,
                        continueWithoutTemplateOnFailure = true
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("uploadButton"),
                shape = RoundedCornerShape(16.dp),
                colors = tealButtonColors
            ) {
                if (uploading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Text("Uploading...", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("Upload Confirmed Dance", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (uiState.showVideoSourceDialog) {
        VideoSourceDialog(
            brandTeal = brandTeal,
            onDismiss = videoUploadVm::dismissVideoSourceDialog,
            onChooseGallery = {
                videoUploadVm.dismissVideoSourceDialog()
                picker.launch("video/*")
            },
            onChooseRecording = {
                videoUploadVm.dismissVideoSourceDialog()
                startLiveRecording()
            }
        )
    }
}

/** Uploads form field. */
@Composable
private fun UploadFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    testTag: String,
    brandTeal: Color,
    minLines: Int = 1,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (value.isNotBlank()) brandTeal
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        singleLine = singleLine,
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = brandTeal,
            focusedLabelColor = brandTeal,
            cursorColor = brandTeal
        )
    )
}

/** Handles video picker. */
@Composable
private fun VideoPicker(
    selectedUri: Uri?,
    selectedVideoLabel: String?,
    uploading: Boolean,
    brandTeal: Color,
    onPick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selectedUri != null) brandTeal.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 1.5.dp,
                color = if (selectedUri != null) brandTeal
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = !uploading) { onPick() }
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedUri != null) brandTeal.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.VideoFile,
                    contentDescription = null,
                    tint = if (selectedUri != null) brandTeal
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedUri != null) "Video selected" else "Choose a video source",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedUri != null) brandTeal
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (selectedUri != null) {
                        selectedVideoLabel ?: "video file"
                    } else {
                        "Tap to choose from gallery or record live"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (selectedUri != null) 0.6f else 0.45f
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selectedUri != null) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = brandTeal,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun DancerReviewPanel(
    videoUri: Uri,
    tracks: List<PoseTrack>,
    selectionPreview: PoseSelectionPreview?,
    selectedTrackIds: Set<String>,
    confirmed: Boolean,
    isAnalyzing: Boolean,
    progress: Float?,
    errorMessage: String?,
    brandTeal: Color,
    onAnalyze: () -> Unit,
    onToggleTrack: (String) -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val thumbnails by produceState<Map<String, Bitmap>>(
        initialValue = emptyMap(),
        videoUri,
        selectionPreview
    ) {
        value = loadUploadTrackThumbnails(context, videoUri, selectionPreview)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (confirmed) brandTeal.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dancer Review",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            confirmed -> "${selectedTrackIds.size} dancer option(s) confirmed"
                            tracks.isNotEmpty() -> "${tracks.size} detected. Select every real dancer before upload."
                            else -> "Analyze the video before upload."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                if (tracks.isNotEmpty() && !isAnalyzing) {
                    TextButton(
                        onClick = onReset,
                        colors = ButtonDefaults.textButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Redo")
                    }
                }
            }

            if (isAnalyzing) {
                LinearProgressIndicator(
                    progress = { progress?.coerceIn(0f, 1f) ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = brandTeal,
                    trackColor = brandTeal.copy(alpha = 0.2f)
                )
                Text(
                    text = "Finding dancers with high-recall analysis...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else if (tracks.isEmpty()) {
                Button(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = Color.White
                    )
                ) {
                    Text("Analyze Dancers")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.forEach { track ->
                        DancerTrackRow(
                            track = track,
                            thumbnail = thumbnails[track.trackId],
                            selected = track.trackId in selectedTrackIds,
                            enabled = !confirmed,
                            brandTeal = brandTeal,
                            onClick = { onToggleTrack(track.trackId) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = confirmed,
                        enabled = false,
                        onClick = {},
                        label = {
                            Text(if (confirmed) "Confirmed" else "${selectedTrackIds.size} selected")
                        }
                    )
                    Button(
                        onClick = onConfirm,
                        enabled = selectedTrackIds.isNotEmpty() && !confirmed,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brandTeal,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Confirm Dancers")
                    }
                }
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DancerTrackRow(
    track: PoseTrack,
    thumbnail: Bitmap?,
    selected: Boolean,
    enabled: Boolean,
    brandTeal: Color,
    onClick: () -> Unit
) {
    val borderColor = if (selected) brandTeal else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 76.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brandTeal.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = track.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.VideoFile,
                    contentDescription = null,
                    tint = brandTeal,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${track.frames.size} frames - visibility ${(track.avgVisibility * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (selected) brandTeal else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

private suspend fun loadUploadTrackThumbnails(
    context: Context,
    videoUri: Uri,
    selectionPreview: PoseSelectionPreview?
): Map<String, Bitmap> = withContext(Dispatchers.IO) {
    if (selectionPreview == null || selectionPreview.candidates.isEmpty()) {
        return@withContext emptyMap()
    }

    val retriever = MediaMetadataRetriever()
    return@withContext try {
        retriever.setDataSource(context, videoUri)
        val frame = retriever.getFrameAtTime(
            selectionPreview.timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return@withContext emptyMap()

        selectionPreview.candidates.associate { candidate ->
            candidate.trackId to cropUploadThumbnail(frame, candidate.bounds)
        }
    } catch (_: Exception) {
        emptyMap()
    } finally {
        runCatching { retriever.release() }
    }
}

private fun cropUploadThumbnail(source: Bitmap, bounds: PoseBounds): Bitmap {
    val left = (bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)

    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    return Bitmap.createScaledBitmap(cropped, 160, 220, true)
}

/** Displays the source picker for upload videos. */
@Composable
private fun VideoSourceDialog(
    brandTeal: Color,
    onDismiss: () -> Unit,
    onChooseGallery: () -> Unit,
    onChooseRecording: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add your dance video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoSourceOption(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = "Choose from gallery",
                    description = "Pick an existing video saved on the device.",
                    brandTeal = brandTeal,
                    onClick = onChooseGallery
                )
                VideoSourceOption(
                    icon = Icons.Outlined.CameraAlt,
                    title = "Record live video",
                    description = "Open the camera, record now, and upload that clip.",
                    brandTeal = brandTeal,
                    onClick = onChooseRecording
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = brandTeal)
            ) {
                Text("Cancel")
            }
        }
    )
}

/** Displays a selectable upload source row. */
@Composable
private fun VideoSourceOption(
    icon: ImageVector,
    title: String,
    description: String,
    brandTeal: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = brandTeal.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brandTeal.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = brandTeal,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}
