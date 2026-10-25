package com.example.chorevo.videouploads

import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import com.example.chorevo.core.AnimatedEmptyState
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.core.ShimmerGrid
import com.example.chorevo.core.ShimmerList
import com.example.chorevo.core.VideoThumbnailTile
import com.example.chorevo.core.rememberFeedPlayerFactory
import com.example.chorevo.videouploads.viewmodel.VideoUploadsViewModel

/** Which uploads a grid shows; null keeps the classic card list. */
enum class UploadsGridFilter { PUBLIC, PRIVATE }

/** Handles video uploads tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUploadsTab(
    onUploadDanceDemo: () -> Unit,
    embedded: Boolean,
    onStatus: (String) -> Unit = {},
    vm: VideoUploadsViewModel = hiltViewModel(),
    refreshKey: Any? = Unit,
    modifier: Modifier = Modifier,
    embeddedInParentScroll: Boolean = false,
    gridFilter: UploadsGridFilter? = null
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    // Upload whose action sheet is open (grid mode).
    var actionItem by remember { mutableStateOf<DanceLibraryModel?>(null) }

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

        if (gridFilter != null) {
            val gridItems = state.myUploads.filter { item ->
                if (gridFilter == UploadsGridFilter.PRIVATE) item.isPrivate else !item.isPrivate
            }
            if (state.refreshing && gridItems.isEmpty()) {
                ShimmerGrid(cells = 6)
            } else {
                UploadsThumbnailGrid(
                    items = gridItems,
                    showingPrivate = gridFilter == UploadsGridFilter.PRIVATE,
                    onTap = { actionItem = it }
                )
            }
        } else {
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

            if (state.refreshing && state.myUploads.isEmpty()) {
                ShimmerList(rows = 3)
            } else if (state.refreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.myUploads.isEmpty() && !state.refreshing) {
                AnimatedEmptyState(
                    icon = Icons.Filled.VideoLibrary,
                    title = "No uploaded videos yet",
                    message = "Your published dances will appear here."
                )
            } else if (state.myUploads.isNotEmpty()) {
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
        }

        Spacer(Modifier.height(8.dp))
    }

    actionItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionItem = null }) {
            UploadActionSheet(
                item = item,
                updatingVisibility = item.danceId in state.updatingVisibilityIds,
                onPlay = { actionItem = null; vm.openPreview(item) },
                onEdit = { actionItem = null; vm.openEdit(item) },
                onToggleVisibility = {
                    actionItem = null
                    vm.setVisibility(
                        item,
                        if (item.isPrivate) DanceLibraryModel.VISIBILITY_PUBLIC else DanceLibraryModel.VISIBILITY_PRIVATE
                    )
                },
                onDelete = { actionItem = null; vm.openDelete(item) }
            )
        }
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
                    DanceStylePicker(
                        value = draft.danceType,
                        onValueChange = vm::editDanceTypeChanged,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "editDanceTypeField",
                        enabled = !state.saving
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

    val feedPlayerFactory = rememberFeedPlayerFactory()
    val exoPlayer = remember {
        feedPlayerFactory.create(repeat = true)
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

/** 3-column thumbnail grid of uploads; private ones get a lock badge. Fixed height so it can sit in a parent scroll. */
@Composable
private fun UploadsThumbnailGrid(
    items: List<DanceLibraryModel>,
    showingPrivate: Boolean,
    onTap: (DanceLibraryModel) -> Unit
) {
    if (items.isEmpty()) {
        if (showingPrivate) {
            AnimatedEmptyState(
                icon = Icons.Filled.Lock,
                title = "No private videos",
                message = "Make an upload private to see it here."
            )
        } else {
            AnimatedEmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = "No public videos yet",
                message = "Tap + to upload your first dance."
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rowItems.forEach { item ->
                    VideoThumbnailTile(
                        videoUrl = item.videoUrl,
                        title = item.title,
                        onClick = { onTap(item) },
                        frameTimeMs = item.coverFrameMs,
                        thumbnailUrl = item.thumbnailUrl,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("uploadTile_${item.danceId}"),
                        badge = if (item.isPrivate) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Private",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(4.dp)
                                        .size(14.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Actions for one upload in the grid. */
@Composable
private fun UploadActionSheet(
    item: DanceLibraryModel,
    updatingVisibility: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onToggleVisibility: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (item.isPrivate) "Private · only you can see it" else "Public",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SheetAction(Icons.Filled.PlayArrow, "Play", onPlay)
        SheetAction(Icons.Filled.Edit, "Edit details", onEdit)
        SheetAction(
            icon = if (item.isPrivate) Icons.Outlined.Public else Icons.Filled.Lock,
            label = if (item.isPrivate) "Make public" else "Make private",
            onClick = onToggleVisibility,
            enabled = !updatingVisibility
        )
        SheetAction(Icons.Filled.Delete, "Delete", onDelete, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

