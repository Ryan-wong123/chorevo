package com.example.chorevo.videouploads

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel

/** Displays the video upload activity screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoUploadActivityScreen(
    onBack: () -> Unit,
    initialVideoUri: Uri? = null, // for instrument test
    showPickButton: Boolean = true, // for instrument test
    videoUploadVm: VideoUploadsViewModel
) {
    val context = LocalContext.current
    val uiState by videoUploadVm.uiState.collectAsState()

    var title by remember { mutableStateOf("") }
    var danceType by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(initialVideoUri) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedUri = uri }

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

    val canUpload = !uploading &&
            selectedUri != null &&
            title.isNotBlank() &&
            danceType.isNotBlank() &&
            description.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Upload Dance", fontWeight = FontWeight.SemiBold)
                },
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
                value = title,
                onValueChange = { title = it },
                label = "Title",
                placeholder = "e.g. Beginner Hip Hop Routine",
                icon = Icons.Outlined.Title,
                testTag = "titleField",
                brandTeal = brandTeal
            )

            UploadFormField(
                value = danceType,
                onValueChange = { danceType = it },
                label = "Dance Type",
                placeholder = "e.g. Hip Hop, Salsa, Ballet",
                icon = Icons.Outlined.MusicNote,
                testTag = "danceTypeField",
                brandTeal = brandTeal
            )

            UploadFormField(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "Describe the moves, difficulty level, or style…",
                icon = Icons.Outlined.Description,
                testTag = "descriptionField",
                brandTeal = brandTeal,
                minLines = 4,
                singleLine = false
            )

            if (showPickButton) {
                VideoPicker(
                    selectedUri = selectedUri,
                    uploading = uploading,
                    brandTeal = brandTeal,
                    onPick = { picker.launch("video/*") }
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
                            "${(p * 100).toInt()}% uploaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
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
                enabled = canUpload,
                onClick = {
                    val uri = selectedUri ?: return@Button
                    videoUploadVm.uploadDance(
                        context = context,
                        title = title,
                        danceType = danceType,
                        description = description,
                        videoUri = uri,
                        generateTemplate = true,
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
                        Text("Uploading…", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text("Upload Dance", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
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
                    text = if (selectedUri != null) "Video selected" else "Pick a video",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedUri != null) brandTeal
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (selectedUri != null)
                        selectedUri.lastPathSegment ?: "video file"
                    else
                        "Tap to choose from your library",
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
