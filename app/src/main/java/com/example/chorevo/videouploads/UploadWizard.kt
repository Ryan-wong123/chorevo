package com.example.chorevo.videouploads

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chorevo.core.shimmer
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Steps of the upload flow, in order. */
enum class UploadStep(val title: String) {
    VIDEO("Video"),
    COVER("Cover"),
    DETAILS("Details"),
    DANCERS("Dancers"),
    PUBLISH("Publish");

    val number: Int get() = ordinal + 1

    fun next(): UploadStep = entries.getOrElse(ordinal + 1) { this }
    fun previous(): UploadStep = entries.getOrElse(ordinal - 1) { this }
}

/** Segmented progress bar with "Step N of 5 · Title". */
@Composable
fun UploadStepIndicator(current: UploadStep, modifier: Modifier = Modifier) {
    val colors = ChorevoTheme.colors
    Column(modifier = modifier.testTag("uploadStepIndicator"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UploadStep.entries.forEach { step ->
                val color by animateColorAsState(
                    if (step.ordinal <= current.ordinal) colors.accent else MaterialTheme.colorScheme.surfaceContainerHighest,
                    label = "stepSegment"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(PillShape)
                        .background(color)
                )
            }
        }
        Text(
            text = "Step ${current.number} of ${UploadStep.entries.size} · ${current.title}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Reads one frame of a local video; shows a shimmer while loading. */
@Composable
fun LocalVideoFrame(
    videoUri: Uri,
    frameMs: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    var frame by remember(videoUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(videoUri, frameMs) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, videoUri)
                    retriever.getFrameAtTime(frameMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
        if (bitmap != null) frame = bitmap
    }

    Box(modifier = modifier.clip(MaterialTheme.shapes.medium)) {
        val current = frame
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        } else {
            Box(Modifier.fillMaxSize().shimmer())
        }
    }
}

/** Duration of a local video in ms, or null while unknown. */
@Composable
fun rememberVideoDurationMs(videoUri: Uri): Long? {
    val context = LocalContext.current
    var duration by remember(videoUri) { mutableStateOf<Long?>(null) }
    LaunchedEffect(videoUri) {
        duration = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, videoUri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    return duration
}

/** Large cover preview, a scrubber, and quick-pick frames along the video. */
@Composable
fun CoverFramePicker(
    videoUri: Uri,
    coverFrameMs: Long,
    onCoverFrameChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val durationMs = rememberVideoDurationMs(videoUri)
    val colors = ChorevoTheme.colors
    var sliderMs by remember(videoUri) { mutableFloatStateOf(coverFrameMs.toFloat()) }
    var previewMs by remember(videoUri) { mutableLongStateOf(coverFrameMs) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a cover", style = MaterialTheme.typography.titleLarge)
        Text(
            "This frame is shown on your video's thumbnail in profiles and grids.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LocalVideoFrame(
            videoUri = videoUri,
            frameMs = previewMs,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(220.dp)
                .aspectRatio(9f / 16f)
                .testTag("coverPreview")
        )

        if (durationMs != null && durationMs > 0) {
            Slider(
                value = sliderMs.coerceIn(0f, durationMs.toFloat()),
                onValueChange = { sliderMs = it },
                onValueChangeFinished = {
                    previewMs = sliderMs.toLong()
                    onCoverFrameChange(previewMs)
                },
                valueRange = 0f..durationMs.toFloat(),
                colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
                modifier = Modifier.testTag("coverSlider")
            )

            val candidates = (1..5).map { i -> durationMs * i / 6 }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { timeMs ->
                    val selected = kotlin.math.abs(timeMs - previewMs) < durationMs / 12
                    LocalVideoFrame(
                        videoUri = videoUri,
                        frameMs = timeMs,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(9f / 16f)
                            .border(
                                BorderStroke(if (selected) 3.dp else 0.dp, colors.accent),
                                MaterialTheme.shapes.medium
                            )
                            .clickable {
                                previewMs = timeMs
                                sliderMs = timeMs.toFloat()
                                onCoverFrameChange(timeMs)
                            }
                    )
                }
            }
        }
    }
}

/** Recap of everything about to be published. */
@Composable
fun PublishSummary(
    videoUri: Uri,
    coverFrameMs: Long,
    title: String,
    danceType: String,
    dancersLabel: String,
    visibility: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LocalVideoFrame(
                videoUri = videoUri,
                frameMs = coverFrameMs,
                modifier = Modifier
                    .width(84.dp)
                    .aspectRatio(9f / 16f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                SummaryLine("Dance type", danceType.ifBlank { "—" })
                SummaryLine("Dancers", dancersLabel)
                SummaryLine(
                    "Visibility",
                    if (visibility == DanceLibraryModel.VISIBILITY_PRIVATE) "Private" else "Public"
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, labelWidth: Dp = 84.dp) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
