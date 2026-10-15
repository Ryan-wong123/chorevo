package com.example.chorevo.videouploads

import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.createCachedFeedExoPlayer
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel

/** Handles video uploads tab. */
@Composable
fun VideoUploadsTab(
    onUploadDanceDemo: () -> Unit,
    embedded: Boolean,
    onStatus: (String) -> Unit = {},
    vm: VideoUploadsViewModel = viewModel(),
    refreshKey: Any? = Unit,
    modifier: Modifier = Modifier,
    embeddedInParentScroll: Boolean = false
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val brandTeal = ChorevoTheme.colors.accent
    val tealButtonColors = ButtonDefaults.buttonColors(
        containerColor = brandTeal,
        contentColor = ChorevoTheme.colors.onAccent,
        disabledContainerColor = brandTeal.copy(alpha = 0.5f),
        disabledContentColor = ChorevoTheme.colors.onAccent.copy(alpha = 0.85f)
    )
    val tealOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = brandTeal
    )
    val tealTextButtonColors = ButtonDefaults.textButtonColors(
        contentColor = brandTeal
    )

    LaunchedEffect(refreshKey) {
        vm.refreshMyUploads()
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            onStatus(it)
            vm.clearStatus()
        }
    }

    /** Handles fmt ts. */
    fun fmtTs(ts: Long?): String {
        if (ts == null) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
    }

    /** Uploads card. */
    @Composable
    fun UploadCard(item: DanceLibraryModel) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)

                if (item.danceType.isNotBlank()) {
                    Text(
                        "Type: ${item.danceType}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(item.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Created: ${fmtTs(item.createdAt)}",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = visibilityIcon(item.visibility),
                        contentDescription = null,
                        tint = brandTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (item.isPrivate) "Private · only you can see it" else "Public",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = item.danceId !in state.updatingVisibilityIds,
                        onClick = {
                            vm.setVisibility(
                                item,
                                if (item.isPrivate) DanceLibraryModel.VISIBILITY_PUBLIC
                                else DanceLibraryModel.VISIBILITY_PRIVATE
                            )
                        },
                        colors = tealTextButtonColors,
                        modifier = Modifier.testTag("toggleVisibility_${item.danceId}")
                    ) {
                        Text(if (item.isPrivate) "Make public" else "Make private")
                    }
                }

                if (!item.isTemplateReady) {
                    val statusText = when (item.templateGenerationStatus.lowercase(Locale.US)) {
                        "queued" -> "Template queued for background processing"
                        "processing" -> "Template is being generated"
                        "failed" -> if (item.templateGenerationError.isNotBlank()) {
                            "Template generation failed: ${item.templateGenerationError}"
                        } else {
                            "Template generation failed"
                        }
                        else -> "Template processing status: ${item.templateGenerationStatus}"
                    }

                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = brandTeal
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.openPreview(item) },
                        colors = tealOutlinedButtonColors
                    ) {
                        Text("Play")
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.openEdit(item) },
                        colors = tealOutlinedButtonColors
                    ) {
                        Text("Edit")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.openDelete(item) },
                        colors = tealButtonColors
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!embedded) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onUploadDanceDemo,
                colors = tealButtonColors
            ) {
                Text("Upload Dance Demo Video")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("My Uploaded Videos", style = MaterialTheme.typography.titleMedium)

            TextButton(
                enabled = !state.refreshing,
                onClick = { vm.refreshMyUploads() },
                colors = tealTextButtonColors
            ) {
                Text(if (state.refreshing) "Loading…" else "Refresh")
            }
        }

        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.myUploads.isEmpty() && !state.refreshing) {
            Text("No uploaded videos yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            if (embeddedInParentScroll) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.myUploads.forEach { item ->
                        UploadCard(item)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.myUploads) { item ->
                        UploadCard(item)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    state.previewItem?.let { item ->
        FullScreenVideoPreview(
            title = "Preview: ${item.title}",
            videoUrl = item.videoUrl,
            onClose = { vm.closePreview() }
        )
    }

    state.editDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { vm.dismissEdit() },
            title = { Text("Edit Upload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = vm::editTitleChanged,
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = draft.danceType,
                        onValueChange = vm::editDanceTypeChanged,
                        label = { Text("Dance Type") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = vm::editDescriptionChanged,
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    VideoVisibilitySelector(
                        visibility = draft.visibility,
                        onVisibilityChange = vm::editVisibilityChanged,
                        accentColor = brandTeal,
                        enabled = !state.saving
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.saving && draft.canSave,
                    onClick = { vm.saveEdit() },
                    colors = tealButtonColors
                ) {
                    Text(if (state.saving) "Saving…" else "Save")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.saving,
                    onClick = { vm.dismissEdit() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    state.deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { vm.dismissDelete() },
            title = { Text("Delete upload?") },
            text = { Text("This will remove the video and its metadata.\n\n${item.title}") },
            confirmButton = {
                Button(
                    enabled = !state.deleting,
                    onClick = { vm.confirmDelete() },
                    colors = tealButtonColors
                ) {
                    Text(if (state.deleting) "Deleting…" else "Delete")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.deleting,
                    onClick = { vm.dismissDelete() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Handles full screen video preview. */
@Composable
private fun FullScreenVideoPreview(
    title: String,
    videoUrl: String,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VideoPreviewPlayer(
                videoUrl = videoUrl,
                isActive = true,
                modifier = Modifier.fillMaxSize()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = Color.White)
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/** Handles video preview player. */
@Composable
private fun VideoPreviewPlayer(
    videoUrl: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        createCachedFeedExoPlayer(context = context, repeat = true)
    }

    LaunchedEffect(videoUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
        exoPlayer.prepare()
    }

    LaunchedEffect(isActive) {
        exoPlayer.playWhenReady = isActive
        if (isActive) exoPlayer.play() else exoPlayer.pause()
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    SharedPlayerView(
        exoPlayer = exoPlayer,
        modifier = modifier
    )
}
