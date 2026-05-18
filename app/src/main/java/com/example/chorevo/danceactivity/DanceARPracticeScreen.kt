package com.example.chorevo.danceactivity

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.example.chorevo.analytics.SessionAnalysisPayload
import com.example.chorevo.analytics.TimelinePoint
import com.example.chorevo.core.CameraBinder
import com.example.chorevo.core.CameraPreview
import com.example.chorevo.core.Ids
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.posedetection.ImageProxyUtils
import com.example.chorevo.posedetection.InstructorAvatarOverlay
import com.example.chorevo.posedetection.PoseDrawSpec
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseLandmarkerHelper
import com.example.chorevo.posedetection.PoseOverlay
import com.example.chorevo.posedetection.PoseSelectionPreview
import com.example.chorevo.posedetection.PoseSimilarityEvaluator
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.posedetection.averageVisibility
import com.example.chorevo.posedetection.poseBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private enum class PracticeMode {
    FULL_ROUTINE,
    STEP_BY_STEP
}

private data class PracticeStepSegment(
    val index: Int,
    val total: Int,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(1L)

    val title: String
        get() = "Step ${index + 1} of $total"
}

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
    var countdownSecondsRemaining by rememberSaveable(item.danceId) { mutableStateOf<Int?>(null) }
    var showReferenceSelector by rememberSaveable(item.danceId) { mutableStateOf(true) }

    val template = item.template
    val availableReferenceTracks = template?.availableTracks().orEmpty()

    var selectedReferenceTrackId by rememberSaveable(item.danceId) { mutableStateOf<String?>(null) }
    var isPracticeActive by rememberSaveable(item.danceId) { mutableStateOf(testMode) }
    var practiceMode by rememberSaveable(item.danceId) { mutableStateOf(PracticeMode.FULL_ROUTINE) }
    var currentStepIndex by rememberSaveable(item.danceId) { mutableIntStateOf(0) }
    val isCountdownActive = countdownSecondsRemaining != null
    val isPracticeFocused = isPracticeActive

    LaunchedEffect(template) {
        selectedReferenceTrackId = template?.resolveTrack(selectedReferenceTrackId)?.trackId
            ?: template?.resolveTrack(null)?.trackId
    }

    val selectedReferenceTrack = remember(template, selectedReferenceTrackId) {
        template?.resolveTrack(selectedReferenceTrackId)
    }

    val selectedTrackTemplate = remember(template, selectedReferenceTrack) {
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

    val practiceSegments = remember(selectedTrackTemplate) {
        derivePracticeStepSegments(selectedTrackTemplate)
    }

    LaunchedEffect(practiceSegments.size) {
        if (practiceSegments.size <= 1) {
            practiceMode = PracticeMode.FULL_ROUTINE
            currentStepIndex = 0
        } else {
            currentStepIndex = currentStepIndex.coerceIn(0, practiceSegments.lastIndex)
        }
    }

    val currentPracticeSegment = remember(practiceSegments, currentStepIndex, practiceMode) {
        if (practiceMode == PracticeMode.STEP_BY_STEP) {
            practiceSegments.getOrNull(currentStepIndex)
        } else {
            null
        }
    }

    val activeTemplate = remember(selectedTrackTemplate, currentPracticeSegment, practiceMode) {
        if (practiceMode == PracticeMode.STEP_BY_STEP && currentPracticeSegment != null) {
            sliceTemplateForStep(selectedTrackTemplate, currentPracticeSegment) ?: selectedTrackTemplate
        } else {
            selectedTrackTemplate
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

    LaunchedEffect(countdownSecondsRemaining) {
        val secondsRemaining = countdownSecondsRemaining ?: return@LaunchedEffect
        delay(1_000)
        if (countdownSecondsRemaining != secondsRemaining) return@LaunchedEffect

        if (secondsRemaining > 1) {
            val nextSecond = secondsRemaining - 1
            countdownSecondsRemaining = nextSecond
            status = "Starting in $nextSecond..."
        } else {
            countdownSecondsRemaining = null
            tsStart = System.currentTimeMillis()
            practiceTimeMs = 0L
            isPracticeActive = true
            status = if (practiceMode == PracticeMode.STEP_BY_STEP && currentPracticeSegment != null) {
                "${currentPracticeSegment.title} in progress"
            } else {
                "Practice in progress"
            }
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

    fun centerPoseOnScreen(flat: List<Float>): List<Float> {
        val bounds = flat.poseBounds(padding = 0f) ?: return flat
        val out = flat.toMutableList()
        val dx = 0.5f - bounds.centerX
        val dy = 0.5f - bounds.centerY
        val n = out.size / 4
        for (i in 0 until n) {
            val base = i * 4
            out[base] = (out[base] + dx).coerceIn(0f, 1f)
            out[base + 1] = (out[base + 1] + dy).coerceIn(0f, 1f)
        }
        return out
    }

    fun doReset(message: String = "Reset") {
        countdownSecondsRemaining = null
        isPracticeActive = false
        showReferenceSelector = true
        tsStart = System.currentTimeMillis()
        practiceTimeMs = 0L
        learnerLandmarks = null
        refLandmarksForCamera = null

        alignmentScore = if (testMode) 0.82 else 0.0
        timingConsistencyScore = if (testMode) 0.91 else 1.0
        smoothnessScore = if (testMode) 0.88 else 1.0
        avgPoseError = if (testMode) 0.1234 else 0.0
        frameCount = if (testMode) 42 else 0
        latestTimelinePoints = emptyList()
        latestSegments = emptyList()

        status = message
    }

    fun startPracticeCountdown() {
        countdownSecondsRemaining = 3
        isPracticeActive = false
        showReferenceSelector = false
        latestTimelinePoints = emptyList()
        latestSegments = emptyList()
        learnerLandmarks = null
        refLandmarksForCamera = null
        alignmentScore = if (testMode) 0.82 else 0.0
        timingConsistencyScore = if (testMode) 0.91 else 1.0
        smoothnessScore = if (testMode) 0.88 else 1.0
        avgPoseError = if (testMode) 0.1234 else 0.0
        frameCount = if (testMode) 42 else 0
        practiceTimeMs = 0L
        status = if (practiceMode == PracticeMode.STEP_BY_STEP && currentPracticeSegment != null) {
            "Starting ${currentPracticeSegment.title.lowercase()} in 3..."
        } else {
            "Starting in 3..."
        }
    }

    fun completeCurrentStep() {
        countdownSecondsRemaining = null
        isPracticeActive = false

        similarityEval?.snapshot(includeSeries = true)?.let { snap ->
            alignmentScore = snap.alignmentScore
            timingConsistencyScore = snap.timingConsistencyScore
            smoothnessScore = snap.smoothnessScore
            avgPoseError = snap.avgPoseError
            frameCount = snap.frameCount
            latestTimelinePoints = snap.timelinePoints
            latestSegments = snap.performanceSegments
        }

        status = when {
            practiceMode != PracticeMode.STEP_BY_STEP || currentPracticeSegment == null -> "Practice paused."
            currentStepIndex < practiceSegments.lastIndex ->
                "${currentPracticeSegment.title} complete. Tap Next Step to continue."
            else -> "${currentPracticeSegment.title} complete. Repeat it or switch to Full Routine to submit a session."
        }
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
        refLandmarksForCamera = ref?.let(::mirrorX)?.let(::centerPoseOnScreen)
    }

    LaunchedEffect(practiceMode, currentPracticeSegment, practiceTimeMs, isPracticeActive, testMode) {
        if (testMode || !isPracticeActive || practiceMode != PracticeMode.STEP_BY_STEP) return@LaunchedEffect

        val segment = currentPracticeSegment ?: return@LaunchedEffect
        if (practiceTimeMs >= segment.durationMs) {
            completeCurrentStep()
        }
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
        countdownSecondsRemaining = null
        if (practiceMode == PracticeMode.STEP_BY_STEP) {
            status = "Step-by-step mode is for rehearsal only. Switch to Full Routine to submit a session."
            return
        }
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
            if (!isPracticeFocused) {
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

                InstructorAvatarOverlay(
                    context = context,
                    landmarks = refLandmarksForCamera,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(660.dp)
                        .height(1080.dp)
                )

                PoseOverlay(
                    modifier = Modifier.fillMaxSize(),
                    poses = listOfNotNull(
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

            if (!isPracticeFocused && showReferenceSelector && availableReferenceTracks.size > 1) {
                ReferenceDancerSelectorCard(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp, top = 12.dp, bottom = 168.dp)
                        .fillMaxHeight()
                        .width(112.dp),
                    videoUrl = item.videoUrl,
                    tracks = availableReferenceTracks,
                    selectionPreview = template?.selectionPreview,
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

            if (!isPracticeFocused && practiceSegments.size > 1) {
                StepPracticeCard(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    brandTeal = brandTeal,
                    practiceMode = practiceMode,
                    currentSegment = currentPracticeSegment,
                    canGoPrevious = currentStepIndex > 0,
                    canGoNext = currentStepIndex < practiceSegments.lastIndex,
                    onSelectFullRoutine = {
                        practiceMode = PracticeMode.FULL_ROUTINE
                        doReset(message = "Full routine ready. Tap Start Practice when ready.")
                    },
                    onSelectStepByStep = {
                        practiceMode = PracticeMode.STEP_BY_STEP
                        currentStepIndex = currentStepIndex.coerceIn(0, practiceSegments.lastIndex)
                        doReset(message = "${practiceSegments[currentStepIndex].title} ready. Tap Start Practice when ready.")
                    },
                    onPreviousStep = {
                        if (currentStepIndex == 0) return@StepPracticeCard
                        currentStepIndex -= 1
                        doReset(message = "${practiceSegments[currentStepIndex].title} ready. Tap Start Practice when ready.")
                    },
                    onNextStep = {
                        if (currentStepIndex >= practiceSegments.lastIndex) return@StepPracticeCard
                        currentStepIndex += 1
                        doReset(message = "${practiceSegments[currentStepIndex].title} ready. Tap Start Practice when ready.")
                    }
                )
            }

            if (isCountdownActive) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("practiceStartCountdown"),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Get Ready",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${countdownSecondsRemaining ?: 0}",
                            fontSize = 52.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = brandTeal
                        )
                        Text(
                            text = "Session starts in",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
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
                    if (!isPracticeFocused) {
                    Text(
                        if (practiceMode == PracticeMode.STEP_BY_STEP) "Step Analytics" else "Live Analytics",
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

                    currentPracticeSegment?.let { segment ->
                        Text(
                            text = "${segment.title} • ${formatPracticeTime(segment.startMs)} to ${formatPracticeTime(segment.endMs)}",
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
                                onClick = {
                                    if (practiceMode == PracticeMode.STEP_BY_STEP) {
                                        completeCurrentStep()
                                    } else {
                                        doSubmit()
                                    }
                                },
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealButtonColors
                            ) {
                                Text(
                                    if (practiceMode == PracticeMode.STEP_BY_STEP) "Finish Step" else "Submit",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { startPracticeCountdown() },
                                enabled = !isCountdownActive,
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealButtonColors
                            ) {
                                Text(
                                    if (isCountdownActive) "Starting..." else "Start Practice",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (practiceMode == PracticeMode.STEP_BY_STEP) {
                                        if (currentStepIndex < practiceSegments.lastIndex) {
                                            currentStepIndex += 1
                                            doReset(
                                                message = "${practiceSegments[currentStepIndex].title} ready. Tap Start Practice when ready."
                                            )
                                        } else {
                                            doReset(message = "Final step ready. Tap Start Practice to repeat it.")
                                        }
                                    } else {
                                        doSubmit()
                                    }
                                },
                                enabled = if (practiceMode == PracticeMode.STEP_BY_STEP) {
                                    practiceSegments.isNotEmpty()
                                } else {
                                    frameCount > 0
                                },
                                contentPadding = PaddingValues(vertical = 8.dp),
                                colors = tealOutlinedButtonColors
                            ) {
                                Text(
                                    when {
                                        practiceMode != PracticeMode.STEP_BY_STEP -> "Submit"
                                        currentStepIndex < practiceSegments.lastIndex -> "Next Step"
                                        else -> "Restart Step"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPracticeCard(
    modifier: Modifier = Modifier,
    brandTeal: Color,
    practiceMode: PracticeMode,
    currentSegment: PracticeStepSegment?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onSelectFullRoutine: () -> Unit,
    onSelectStepByStep: () -> Unit,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .testTag("stepPracticeCard"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Practice Mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSelectFullRoutine,
                    enabled = practiceMode != PracticeMode.FULL_ROUTINE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                ) {
                    Text("Full Routine")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onSelectStepByStep,
                    enabled = practiceMode != PracticeMode.STEP_BY_STEP,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = Color.White
                    )
                ) {
                    Text("Step-by-Step")
                }
            }

            currentSegment?.let { segment ->
                Text(
                    text = "${segment.title} • ${formatPracticeTime(segment.startMs)} to ${formatPracticeTime(segment.endMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onPreviousStep,
                        enabled = canGoPrevious,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Previous")
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onNextStep,
                        enabled = canGoNext,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

private fun derivePracticeStepSegments(template: PoseTemplate?): List<PracticeStepSegment> {
    val source = template ?: return emptyList()
    val frames = source.frames.sortedBy { it.tMs }
    if (frames.size < 2) return emptyList()

    val framesPerStep = (source.fps.coerceAtLeast(1) * 4).coerceAtLeast(4)
    val minLastStepFrames = (source.fps.coerceAtLeast(1) * 2).coerceAtLeast(2)
    val chunks = frames.chunked(framesPerStep).toMutableList()

    if (chunks.size > 1 && chunks.last().size < minLastStepFrames) {
        val trailingChunk = chunks.removeAt(chunks.lastIndex)
        chunks[chunks.lastIndex] = chunks.last() + trailingChunk
    }

    val total = chunks.size
    return chunks.mapIndexed { index, chunk ->
        val startMs = chunk.first().tMs
        val rawEndMs = chunk.last().tMs
        val endMs = if (rawEndMs > startMs) rawEndMs else startMs + 1L
        PracticeStepSegment(
            index = index,
            total = total,
            startMs = startMs,
            endMs = endMs
        )
    }
}

private fun sliceTemplateForStep(
    template: PoseTemplate?,
    segment: PracticeStepSegment
): PoseTemplate? {
    val source = template ?: return null
    val normalizedStart = segment.startMs

    fun rebaseFrames(frames: List<PoseFrame>): List<PoseFrame> {
        return frames
            .filter { it.tMs in segment.startMs..segment.endMs }
            .map { it.copy(tMs = (it.tMs - normalizedStart).coerceAtLeast(0L)) }
    }

    val segmentFrames = rebaseFrames(source.frames)
    if (segmentFrames.isEmpty()) return null

    val segmentTracks = source.tracks.mapNotNull { track ->
        val rebased = rebaseFrames(track.frames)
        if (rebased.isEmpty()) null else track.copy(frames = rebased)
    }

    return source.copy(
        frames = segmentFrames,
        tracks = segmentTracks
    )
}

private fun formatPracticeTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun ReferenceDancerSelectorCard(
    modifier: Modifier = Modifier,
    videoUrl: String,
    tracks: List<PoseTrack>,
    selectionPreview: PoseSelectionPreview?,
    selectedTrackId: String?,
    brandTeal: Color,
    onTrackSelected: (String) -> Unit
) {
    val thumbnails by produceState<Map<String, Bitmap>>(
        initialValue = emptyMap(),
        videoUrl,
        selectionPreview
    ) {
        value = loadReferenceTrackThumbnails(videoUrl, selectionPreview)
    }

    Card(
        modifier = modifier.testTag("dancerSelectorCard"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Reference",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            tracks.forEach { track ->
                val isSelected = track.trackId == selectedTrackId
                val thumbnail = thumbnails[track.trackId]
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
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = track.label,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = track.label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center
                            )
                        }
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
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = track.label,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
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
}

private suspend fun loadReferenceTrackThumbnails(
    videoUrl: String,
    selectionPreview: PoseSelectionPreview?
): Map<String, Bitmap> = withContext(Dispatchers.IO) {
    if (videoUrl.isBlank() || selectionPreview == null || selectionPreview.candidates.isEmpty()) {
        return@withContext emptyMap()
    }

    val retriever = MediaMetadataRetriever()
    return@withContext try {
        retriever.setDataSource(videoUrl, emptyMap())
        val frame = retriever.getFrameAtTime(
            selectionPreview.timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return@withContext emptyMap()

        selectionPreview.candidates.associate { candidate ->
            candidate.trackId to cropReferenceThumbnail(frame, candidate.bounds)
        }
    } catch (_: Exception) {
        emptyMap()
    } finally {
        runCatching { retriever.release() }
    }
}

private fun cropReferenceThumbnail(
    source: Bitmap,
    bounds: com.example.chorevo.posedetection.PoseBounds
): Bitmap {
    val left = (bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)

    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    val targetWidth = 160
    val targetHeight = 220
    return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
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
