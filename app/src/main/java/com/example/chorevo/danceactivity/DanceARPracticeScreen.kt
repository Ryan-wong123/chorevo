package com.example.chorevo.danceactivity

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.analytics.PerformanceSegment
import com.example.chorevo.analytics.SegmentLabel
import com.example.chorevo.analytics.SessionAnalysisPayload
import com.example.chorevo.analytics.TimelinePoint
import com.example.chorevo.core.CameraBinder
import com.example.chorevo.core.CameraPreview
import com.example.chorevo.core.Ids
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.posedetection.ImageProxyUtils
import com.example.chorevo.posedetection.PoseDrawSpec
import com.example.chorevo.posedetection.PoseLandmarkerHelper
import com.example.chorevo.posedetection.PoseOverlay
import com.example.chorevo.posedetection.PoseSimilarityEvaluator
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.posedetection.RenderMode
import com.example.chorevo.posedetection.averageVisibility
import com.example.chorevo.posedetection.poseBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Displays the dance ar practice screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceARPracticeScreen(
    item: DanceLibraryModel,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    vm: DanceLibraryListViewModel,
    achievementVm: AchievementViewModel,
    uid: String,
    testMode: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiScope = rememberCoroutineScope()

    var tsStart by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var practiceTimeMs by remember { mutableLongStateOf(0L) }
    var hasCamPermission by remember { mutableStateOf(testMode) }
    var learnerLandmarks by remember { mutableStateOf<List<Float>?>(null) }
    var refLandmarksForCamera by remember { mutableStateOf<List<Float>?>(null) }

    var alignmentScore by remember { mutableDoubleStateOf(if (testMode) 0.82 else 0.0) }
    var timingConsistencyScore by remember { mutableDoubleStateOf(if (testMode) 0.91 else 1.0) }
    var smoothnessScore by remember { mutableDoubleStateOf(if (testMode) 0.88 else 1.0) }
    var avgPoseError by remember { mutableDoubleStateOf(if (testMode) 0.1234 else 0.0) }
    var frameCount by remember { mutableIntStateOf(if (testMode) 42 else 0) }

    var latestTimelinePoints by remember { mutableStateOf<List<TimelinePoint>>(emptyList()) }
    var latestSegments by remember { mutableStateOf<List<PerformanceSegment>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(if (testMode) "Test Mode" else null) }

    val template = item.template
    val availableReferenceTracks = template?.availableTracks().orEmpty()

    var selectedReferenceTrackId by rememberSaveable(item.danceId) { mutableStateOf<String?>(null) }
    var isPracticeActive by rememberSaveable(item.danceId) { mutableStateOf(testMode) }

    LaunchedEffect(template) {
        selectedReferenceTrackId = template?.resolveTrack(selectedReferenceTrackId)?.trackId
            ?: template?.resolveTrack(null)?.trackId
    }

    val selectedReferenceTrack = remember(template, selectedReferenceTrackId) {
        template?.resolveTrack(selectedReferenceTrackId)
    }

    val activeTemplate = remember(template, selectedReferenceTrack) {
        val frames = selectedReferenceTrack?.frames ?: template?.frames.orEmpty()
        if (template == null || frames.isEmpty()) {
            null
        } else {
            template.copy(
                frames = frames,
                tracks = selectedReferenceTrack?.let(::listOf).orEmpty(),
                defaultTrackId = selectedReferenceTrack?.trackId ?: template.defaultTrackId
            )
        }
    }

    val camPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasCamPermission = it
        }

    val uploadState by vm.uploadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val achievementState by achievementVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(achievementState.errorMessage) {
        val msg = achievementState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        achievementVm.clearError()
    }

    LaunchedEffect(uploadState.message) {
        val msg = uploadState.message ?: return@LaunchedEffect
        if (msg != "Submitted practice result") {
            snackbarHostState.showSnackbar(msg)
        }
        vm.clearUploadMessage()
    }

    LaunchedEffect(Unit) {
        if (!testMode) {
            camPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(tsStart, isPracticeActive) {
        if (!isPracticeActive) {
            practiceTimeMs = 0L
            return@LaunchedEffect
        }

        while (isPracticeActive) {
            practiceTimeMs = System.currentTimeMillis() - tsStart
            delay(33)
        }
    }

    val poseHelper = remember(hasCamPermission, testMode) {
        if (hasCamPermission && !testMode) PoseLandmarkerHelper(context) else null
    }

    DisposableEffect(poseHelper) {
        onDispose { poseHelper?.close() }
    }

    DisposableEffect(Unit) {
        onDispose { CameraBinder.unbind() }
    }

    val similarityEval = remember(activeTemplate, testMode) {
        if (testMode) {
            null
        } else {
            activeTemplate?.frames?.takeIf { it.isNotEmpty() }?.let {
                PoseSimilarityEvaluator(template = activeTemplate, mirrorReferenceX = true)
            }
        }
    }

    fun mirrorX(flat: List<Float>): List<Float> {
        if (flat.isEmpty()) return flat
        val out = flat.toMutableList()
        val n = out.size / 4
        for (i in 0 until n) {
            val base = i * 4
            out[base] = 1f - out[base]
        }
        return out
    }

    fun doReset(message: String = "Reset") {
        isPracticeActive = false
        tsStart = System.currentTimeMillis()
        practiceTimeMs = 0L
        learnerLandmarks = null

        alignmentScore = if (testMode) 0.82 else 0.0
        timingConsistencyScore = if (testMode) 0.91 else 1.0
        smoothnessScore = if (testMode) 0.88 else 1.0
        avgPoseError = if (testMode) 0.1234 else 0.0
        frameCount = if (testMode) 42 else 0
        latestTimelinePoints = emptyList()
        latestSegments = emptyList()

        status = message
    }

    fun startPractice() {
        tsStart = System.currentTimeMillis()
        practiceTimeMs = 0L
        latestTimelinePoints = emptyList()
        latestSegments = emptyList()
        learnerLandmarks = null
        refLandmarksForCamera = null
        alignmentScore = if (testMode) 0.82 else 0.0
        timingConsistencyScore = if (testMode) 0.91 else 1.0
        smoothnessScore = if (testMode) 0.88 else 1.0
        avgPoseError = if (testMode) 0.1234 else 0.0
        frameCount = if (testMode) 42 else 0
        isPracticeActive = true
        status = "Practice in progress"
    }

    LaunchedEffect(activeTemplate, practiceTimeMs, testMode, isPracticeActive) {
        if (testMode || !isPracticeActive) {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        val frames = activeTemplate?.frames ?: run {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        if (frames.isEmpty()) {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        val ref = frames.minByOrNull { abs(it.tMs - practiceTimeMs) }?.landmarks
        refLandmarksForCamera = ref?.let(::mirrorX)
    }

    val currentPracticeTimeMs by rememberUpdatedState(practiceTimeMs)

    val analyzer = remember(similarityEval, poseHelper, hasCamPermission, testMode) {
        ImageAnalysis.Analyzer { imageProxy ->
            if (testMode || !hasCamPermission) {
                imageProxy.close()
                return@Analyzer
            }

            val helper = poseHelper
            if (helper == null) {
                imageProxy.close()
                return@Analyzer
            }

            try {
                val bmp = imageProxy.toBitmapFrontCamera()
                val res = helper.detect(bmp)
                val detections = res.landmarks().mapNotNull { pose ->
                    if (pose.isEmpty()) return@mapNotNull null
                    val flat = ArrayList<Float>(pose.size * 4)
                    for (lm in pose) {
                        flat.add(lm.x())
                        flat.add(lm.y())
                        flat.add(lm.z())
                        flat.add(lm.visibility().orElse(1.0f))
                    }
                    flat
                }

                val primaryPose = detections.maxByOrNull { detection ->
                    val prominence = detection.poseBounds()?.area ?: 0f
                    prominence * max(detection.averageVisibility(), 0.1f)
                }

                if (!primaryPose.isNullOrEmpty()) {
                    if (!isPracticeActive) {
                        uiScope.launch {
                            learnerLandmarks = primaryPose
                        }
                        return@Analyzer
                    }

                    val ev = similarityEval
                    val snap = if (ev != null) {
                        ev.onFrame(primaryPose, currentPracticeTimeMs)
                        ev.snapshot(includeSeries = false)
                    } else {
                        null
                    }

                    uiScope.launch {
                        learnerLandmarks = primaryPose

                        if (snap != null) {
                            alignmentScore = snap.alignmentScore
                            timingConsistencyScore = snap.timingConsistencyScore
                            smoothnessScore = snap.smoothnessScore
                            avgPoseError = snap.avgPoseError
                            frameCount = snap.frameCount
                        } else {
                            frameCount += 1
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                imageProxy.close()
            }
        }
    }

    fun doSubmit() {
        if (!isPracticeActive && frameCount == 0) {
            status = "Tap Start Practice before submitting a dance session."
            return
        }

        val educatorUid = item.educatorUid?.trim().takeUnless { it.isNullOrBlank() }
        if (educatorUid == null) {
            status = "This dance is missing educator ownership data, so analytics cannot be submitted."
            return
        }

        val tsEnd = System.currentTimeMillis()
        val durationMs = tsEnd - tsStart
        val ev = similarityEval

        isPracticeActive = false

        val snapshot = ev?.snapshot(includeSeries = true)
        val payloadTimeline = snapshot?.timelinePoints ?: latestTimelinePoints
        val payloadSegments = snapshot?.performanceSegments ?: latestSegments

        latestTimelinePoints = payloadTimeline
        latestSegments = payloadSegments

        val analysisPayload = SessionAnalysisPayload(
            danceId = item.danceId,
            alignmentScore = alignmentScore,
            timingConsistencyScore = timingConsistencyScore,
            smoothnessScore = smoothnessScore,
            avgPoseError = avgPoseError,
            frames = frameCount,
            educatorUid = educatorUid,
            videoUrl = item.videoUrl,
            videoPositionMs = practiceTimeMs,
            referenceTrackId = selectedReferenceTrack?.trackId,
            referenceTrackLabel = selectedReferenceTrack?.label,
            timelinePoints = payloadTimeline,
            performanceSegments = payloadSegments
        )

        val session = DanceSessionEntity(
            sessionId = Ids.newSessionId(),
            tsStart = tsStart,
            tsEnd = tsEnd,
            activityName = item.title,
            activityType = "LIVE_CAMERA_TEMPLATE",
            durationMs = durationMs,
            smoothnessScore = smoothnessScore,
            balanceScore = 1.0,
            timingConsistencyScore = timingConsistencyScore,
            alignmentScore = alignmentScore,
            qualityFlag = if (alignmentScore >= 0.75) "GOOD" else "DONE",
            analysisJson = analysisPayload.toJson()
        )

        status = "Submitting practice result..."

        scope.launch {
            try {
                vm.saveSessionAndUpload(
                    session = session,
                    extraAnalytics = emptyMap(),
                    onSuccess = { },
                    uid = uid
                )

                achievementVm.processCompletedSession(
                    uid = uid,
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore
                )

                status = null
            } catch (e: Exception) {
                status = "Error: ${e.message ?: "Something went wrong"}"
            }
        }
    }

    val brandTeal = Color(0xFF00B8C8)
    val tealButtonColors = ButtonDefaults.buttonColors(
        containerColor = brandTeal,
        contentColor = Color.White,
        disabledContainerColor = brandTeal.copy(alpha = 0.5f),
        disabledContentColor = Color.White.copy(alpha = 0.85f)
    )
    val tealOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = brandTeal
    )

    if (achievementState.showAchievementDialog) {
        AchievementSummaryDialog(
            sessionPoints = achievementState.sessionPoints,
            totalPoints = achievementState.totalPoints,
            currentLevel = achievementState.currentLevel,
            leveledUp = achievementState.leveledUp,
            previousLevel = achievementState.previousLevel,
            newBadges = achievementState.newlyUnlocked,
            brandTeal = brandTeal,
            onDismiss = {
                achievementVm.dismissAchievementDialog()
                onNavigateToProfile()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AR Practice: ${item.title}") },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Back")
                    }
                }
            )
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (!hasCamPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission required.")
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { camPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = tealButtonColors
                    ) {
                        Text("Grant Permission")
                    }
                }
                return@Box
            }

            if (!testMode) {
                CameraPreview(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    analyzer = analyzer,
                    modifier = Modifier.fillMaxSize()
                )

                PoseOverlay(
                    modifier = Modifier.fillMaxSize(),
                    poses = listOfNotNull(
                        refLandmarksForCamera?.let {
                            PoseDrawSpec(
                                landmarks = it,
                                color = Color.Cyan,
                                alpha = 0.35f,
                                jointRadius = 6f,
                                boneWidth = 5f,
                                renderMode = RenderMode.AVATAR
                            )
                        },
                        learnerLandmarks?.let {
                            PoseDrawSpec(
                                landmarks = it,
                                color = Color.Green,
                                alpha = 0.95f,
                                jointRadius = 7f,
                                boneWidth = 6f
                            )
                        }
                    )
                )
            }

            if (availableReferenceTracks.size > 1) {
                ReferenceDancerSelectorCard(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                        .width(112.dp),
                    tracks = availableReferenceTracks,
                    selectedTrackId = selectedReferenceTrack?.trackId,
                    brandTeal = brandTeal,
                    onTrackSelected = { trackId ->
                        if (trackId == selectedReferenceTrack?.trackId) return@ReferenceDancerSelectorCard
                        val selectedTrack = availableReferenceTracks.firstOrNull { it.trackId == trackId }
                            ?: return@ReferenceDancerSelectorCard
                        selectedReferenceTrackId = selectedTrack.trackId
                        doReset(message = "Using ${selectedTrack.label}. Tap Start Practice when ready.")
                    }
                )
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(0.92f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Live Analytics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    selectedReferenceTrack?.takeIf { availableReferenceTracks.size > 1 }?.let { track ->
                        Text(
                            text = "Reference: ${track.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = brandTeal
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Align ${(alignmentScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Timing ${(timingConsistencyScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Smooth ${(smoothnessScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        text = "Err ${"%.4f".format(avgPoseError)} • Frames $frameCount • ${practiceTimeMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPracticeActive) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { doReset(message = "Practice reset. Tap Start Practice when ready.") },
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealOutlinedButtonColors
                            ) {
                                Text("Reset", style = MaterialTheme.typography.bodyMedium)
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { doSubmit() },
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealButtonColors
                            ) {
                                Text("Submit", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { startPractice() },
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealButtonColors
                            ) {
                                Text("Start Practice", style = MaterialTheme.typography.bodyMedium)
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { doSubmit() },
                                enabled = frameCount > 0,
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealOutlinedButtonColors
                            ) {
                                Text("Submit", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceDancerSelectorCard(
    modifier: Modifier = Modifier,
    tracks: List<PoseTrack>,
    selectedTrackId: String?,
    brandTeal: Color,
    onTrackSelected: (String) -> Unit
) {
    Card(
        modifier = modifier.testTag("dancerSelectorCard"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Reference",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            tracks.forEach { track ->
                val isSelected = track.trackId == selectedTrackId
                if (isSelected) {
                    Button(
                        onClick = { onTrackSelected(track.trackId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dancerChip_${track.trackId}"),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brandTeal,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = track.label,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onTrackSelected(track.trackId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dancerChip_${track.trackId}"),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            text = track.label,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementSummaryDialog(
    sessionPoints: Int,
    totalPoints: Int,
    currentLevel: AchievementLevel,
    leveledUp: Boolean,
    previousLevel: AchievementLevel,
    newBadges: List<BadgeDefinition>,
    brandTeal: Color,
    onDismiss: () -> Unit
) {
    val nextLevel = nextLevelForPoints(totalPoints)
    val progressToNext = if (nextLevel != null) {
        val range = nextLevel.pointsRequired - currentLevel.pointsRequired
        val soFar = totalPoints - currentLevel.pointsRequired
        (soFar.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (leveledUp) {
                    Text("Level Up!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${previousLevel.emoji} ${previousLevel.title} -> ${currentLevel.emoji} ${currentLevel.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = brandTeal,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        "${currentLevel.emoji} ${currentLevel.title}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "+$sessionPoints pts",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = brandTeal
                    )
                    Text(
                        "this session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$totalPoints total points",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            currentLevel.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            nextLevel?.title ?: "Max Level",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progressToNext },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = brandTeal,
                        trackColor = brandTeal.copy(alpha = 0.2f)
                    )
                    if (nextLevel != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${nextLevel.pointsRequired - totalPoints} pts to ${nextLevel.title}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }

                if (newBadges.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "New Badges Unlocked!",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        newBadges.forEach { badge ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = brandTeal.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(badge.emoji, fontSize = 24.sp)
                                Column {
                                    Text(
                                        badge.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        badge.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = Color.White
                    )
                ) {
                    Text("Awesome!", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun ImageProxy.toBitmapFrontCamera(): Bitmap {
    val bmp = ImageProxyUtils.toBitmap(this)
    val rotation = imageInfo.rotationDegrees.toFloat()

    val matrix = Matrix().apply {
        postRotate(rotation)
        postScale(
            -1f,
            1f,
            bmp.width / 2f,
            bmp.height / 2f
        )
    }

    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}
